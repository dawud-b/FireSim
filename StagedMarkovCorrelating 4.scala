// wszogg start THIS ENTIRE FILE
package barf

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.subsystem.{CacheBlockBytes}

case class StagedMarkovCorrelatingPrefetcherParams(
	entries: Int = 1024,    // Number of entries for the correlation table
	predictors: Int = 4,    // Number of predicted address per miss address
	requests: Int = 1,      // Number of prefetch requests per address match
	history: Int = 4,       // Number of history states (including 0)
	queue: Int = 2,         // Size of queue
	log: Int = 0			// Should we log outputs or not
) extends CanInstantiatePrefetcher {
	def desc() = "Markov FSM Correlating Prefetcher"
	def instantiate()(implicit p: Parameters) = Module(new StagedMarkovCorrelatingPrefetcher(this)(p))
}

/**
	* Correlating prefetcher that utilizes Markov prefetchers to bring temporal chains that have been seen before
	*
	* Uses the alternative SRAM API referred to at: https://www.chisel-lang.org/docs/explanations/memories
	* Model based on https://ieeexplore.ieee.org/stamp/stamp.jsp?arnumber=752653
*/
class StagedMarkovCorrelatingPrefetcher(params: StagedMarkovCorrelatingPrefetcherParams)(implicit p: Parameters) extends AbstractPrefetcher()(p) {
	// Constants
	val ADDR_LENGTH             = 40		                        // Bit-length of memory address

	val NUM_ENTRIES             = params.entries                    // Number of entries in the predictor table
	val NUM_PREDICTORS          = params.predictors                 // Number of predictors per address entry
	val NUM_REQUESTS            = params.requests                   // Number of prefetch requests that can be sent

	val MAX_ENTRY_INDEX         = NUM_ENTRIES - 1                   // Highest index number for the table entries
	val MAX_HISTORY             = params.history                    // Largest counter value for the predictor history tracker
	val QUEUE_SIZE              = params.queue                      // Size of the queue (assuming that it is not '0')

	val BITSIZE_NUM_ENTRIES     = log2Floor(NUM_ENTRIES) + 1        // Number of bits to store the NUMBER OF ENTRIES in the table
	val BITSIZE_NUM_PREDICTORS  = log2Ceil(NUM_PREDICTORS)          // Number of bits to represent each predictor  (0 - NUM_PREDICTORS - 1)
	val BITSIZE_QUEUE_SIZE      = log2Floor(QUEUE_SIZE) + 1         // Number of bits to represent current number of queue entries
	val BITSIZE_MAX_ENTRY_INDEX = log2Ceil(NUM_ENTRIES)             // Number of bits to index into each table entry (0 - NUM_ENTRIES - 1)
	val BITSIZE_MAX_HISTORY     = log2Ceil(MAX_HISTORY)             // Number of bits to represent each history value (0 - MAX_HISTORY - 1)
	val BITSIZE_MAX_PREDICTORS  = log2Floor(NUM_PREDICTORS) + 1     // Number of bits to represent the number "NUM_PREDICTORS" for tracking purposes

	val HISTORY_SIZE_SIGNED     = BITSIZE_MAX_HISTORY + 1           // Number of bits to carry out confidence calculations to take signed values into consideration

	// ===================================================================================

	// Memory / Reg bank Logic for address matching
	val table_missAddr      = RegInit(VecInit(Seq.fill(NUM_ENTRIES)(0.U(ADDR_LENGTH.W))))               // Register bank for miss addresses
	val table_write         = RegInit(VecInit(Seq.fill(NUM_ENTRIES)(false.B)))                          // Register bank for whether or not address was a read or write
	val table_lru_state     = RegInit(VecInit(Seq.fill(NUM_ENTRIES)(0.U(BITSIZE_MAX_ENTRY_INDEX.W))))   // Vector of lru state for tag values

	// Table value tracking
	val table_full          = WireDefault(false.B)                                 // Table full signal
	val oldest_entry        = WireDefault(0.U(BITSIZE_MAX_ENTRY_INDEX.W))       // Oldest entry to be replaced
	val curr_entry_num      = RegInit(0.U(BITSIZE_NUM_ENTRIES.W))               // Number of valid entries in miss table

	val entry_match         = WireDefault(false.B)                              // Do the address and r/w operation match
	val entry_match_idx     = WireDefault(0.U(BITSIZE_MAX_ENTRY_INDEX.W))       // Index where an extry match was found

	// Registers
	val reg_prev_snoop_match	= RegInit(false.B)							// Was the previous snoop a match
	val reg_prev_snoop_miss    	= RegInit(false.B)                         	// Was the previous snoop a miss
	val reg_prev_snoop_idx     	= RegInit(0.U(BITSIZE_MAX_ENTRY_INDEX.W))  	// Index of the previous snoop

	val reg_curr_snoop_match	= RegInit(false.B)							// Was the current snoop a match
	val reg_curr_snoop_miss 	= RegInit(false.B)                         	// Was the current snoop a miss
	val reg_curr_snoop_idx  	= RegInit(0.U(BITSIZE_MAX_ENTRY_INDEX.W))	// Index of the current snoop

	val reg_curr_snoop_addr		= RegInit(0.U(ADDR_LENGTH.W))				// Register for the current snoop address
	val reg_curr_snoop_write	= RegInit(false.B)							// Register for the current snoop write / read

	val reg_prev_addrDataMem    = RegInit(VecInit(Seq.fill(NUM_PREDICTORS)(0.U(ADDR_LENGTH.W))))             // Vector-reg containing the address data from memory for the previous access
	val reg_prev_validMem       = RegInit(VecInit(Seq.fill(NUM_PREDICTORS)(false.B)))                        // Vector-reg containing the valid data from memory for the previous access
	val reg_prev_writeMem       = RegInit(VecInit(Seq.fill(NUM_PREDICTORS)(false.B)))                        // Vector-reg containing the write bit data from memory for the previous access
	val reg_prev_confMem        = RegInit(VecInit(Seq.fill(NUM_PREDICTORS)(0.U(BITSIZE_MAX_HISTORY.W))))     // Vector-reg contatining the confidence values from memory for the previous access

	val reg_curr_addrDataMem 	= RegInit(VecInit(Seq.fill(NUM_PREDICTORS)(0.U(ADDR_LENGTH.W))))             // Vector-reg containing the address data from memory for the current access
	val reg_curr_validMem       = RegInit(VecInit(Seq.fill(NUM_PREDICTORS)(false.B)))                        // Vector-reg containing the valid data from memory for the current access
	val reg_curr_writeMem       = RegInit(VecInit(Seq.fill(NUM_PREDICTORS)(false.B)))                        // Vector-reg containing the write bit data from memory for the current access
	val reg_curr_confMem      	= RegInit(VecInit(Seq.fill(NUM_PREDICTORS)(0.U(BITSIZE_MAX_HISTORY.W))))     // Vector-reg contatining the confidence values from memory for the current access

	// Predictor memory banks, vectors, and match logic
	val predictor_banks = for (i <- 0 until NUM_PREDICTORS) yield {
		val mem_bank = Module(new Table_Mem_Staged(BITSIZE_MAX_ENTRY_INDEX, NUM_ENTRIES, ADDR_LENGTH, BITSIZE_MAX_HISTORY))
		mem_bank
	}

	val predictor_memBank         	= VecInit(predictor_banks.map(_.io))

	val predictor_matchExists      	= RegInit(false.B)                                                           // Does the address already exist in the last access' predictor for the match access
	val predictor_matchExists_idx 	= RegInit(0.U(BITSIZE_NUM_PREDICTORS.W))                                     // Index for the match of last access' predictor (if any) for the match access

	// Queue + Prefetch request vector
	val pipe_requests		= RegInit(VecInit(Seq.fill(NUM_REQUESTS)(0.U.asTypeOf(new Prefetch_Entry_Staged(ADDR_LENGTH)))))
	val q 					= Module(new Queue(new Prefetch_Entry_Staged(40), entries = QUEUE_SIZE, pipe = true))

	// FSM Signals
	val sIdle :: sReadPredictors :: sNeedUpdate :: sUpdatePredictors :: sNewPredictor :: Nil = Enum(5)
	val state = RegInit(sIdle)

	// Other signals
	val move_valid_vector   	= RegInit(VecInit(Seq.fill(NUM_PREDICTORS)(false.B)))      // Valid signal for rearranging the predictor address order
	val move_valid          	= WireDefault(false.B)                                         // Is there here is a valid move
	val move_idx            	= WireDefault(0.U(BITSIZE_NUM_PREDICTORS.W))                   // Index to move the current predictor value to

	val predictors_num_valid    = RegInit(0.U(BITSIZE_MAX_PREDICTORS.W))            // Current number of predictor entries that are valid in the match address read
	val predictors_full    		= WireDefault(false.B)                              // Whether or not every single predictor has an entry or not for the match read

	val new_conf            	= RegInit(VecInit(Seq.fill(NUM_PREDICTORS)(0.U(BITSIZE_MAX_HISTORY.W))))   // New confidence value
	val conf_dif            	= WireDefault(VecInit(Seq.fill(NUM_PREDICTORS)(0.S(HISTORY_SIZE_SIGNED.W))))   // Difference in confidence between 2 predictor entries

	// ---------------------------------
	// DEBUGGING SIGNALS

	val read_valid   	= WireDefault(VecInit(Seq.fill(NUM_PREDICTORS)(false.B)))    // Bitwise signal for which match predictor banks were read
	val write_valid 	= WireDefault(VecInit(Seq.fill(NUM_PREDICTORS)(false.B)))    // Bitwise signal for which match predictor banks were written to

	val invValidVec_debug       = WireDefault(VecInit(Seq.fill(NUM_PREDICTORS)(false.B)))
	val firstInvalidIdx_debug   = WireDefault(0.U(BITSIZE_NUM_PREDICTORS.W))
	val allValid_debug          = WireDefault(false.B)
	val matchVec_debug          = WireDefault(VecInit(Seq.fill(NUM_PREDICTORS)(false.B)))
	val matchConfs_debug        = WireDefault(VecInit(Seq.fill(NUM_PREDICTORS)(0.U(BITSIZE_MAX_HISTORY.W))))

	val state_idle				= WireDefault(false.B)
	val state_readPredictors	= WireDefault(false.B)
	val state_needUpdate		= WireDefault(false.B)
	val state_newPredictor		= WireDefault(false.B)
	val state_updatePredictors	= WireDefault(false.B)

	// ==================================================================================================================

	// Global init logic
	table_full          := curr_entry_num === NUM_ENTRIES.U
	predictors_full    	:= predictors_num_valid === NUM_PREDICTORS.U

	pipe_requests(NUM_REQUESTS - 1).valid	:= false.B
	pipe_requests(NUM_REQUESTS - 1).address	:= 0.U
	pipe_requests(NUM_REQUESTS - 1).write	:= false.B
	for (i <- 0 until NUM_REQUESTS - 1) {
		pipe_requests(i) := pipe_requests(i + 1)
	}
	q.io.enq.valid	:= pipe_requests(0).valid
	q.io.enq.bits	:= pipe_requests(0)
	q.io.deq.ready	:= true.B
	

	// Default to complete memory initialization
	// Initialization of vector registers
	for (i <- 0 until NUM_PREDICTORS) {
		// Registers
		predictor_memBank(i).rdEna        := false.B
		predictor_memBank(i).rdIdx        := 0.U

		predictor_memBank(i).wrEna        := false.B
		predictor_memBank(i).wrIdx        := 0.U
		predictor_memBank(i).wrAddr       := 0.U
		predictor_memBank(i).wrValidBit   := false.B
		predictor_memBank(i).wrWriteBit   := false.B
		predictor_memBank(i).wrConf       := 0.U
	}

	// ==================================================================================================================

	// FSM Logic

	switch(state) {
		// Waiting for a valid snoop input
		is(sIdle) {
			// Default State
			state_idle 				:= true.B
			state   				:= sIdle
			reg_curr_snoop_addr		:= io.snoop.bits.address
			reg_curr_snoop_write	:= io.snoop.bits.write

			// Does a match exist?
			for (i <- 0 until NUM_ENTRIES) {
				// Check if an entry matches our snoop
				when (io.snoop.valid && (table_missAddr(i) === io.snoop.bits.address) && (i.U < curr_entry_num) && (table_write(i) === io.snoop.bits.write)) {
					entry_match      := true.B
					entry_match_idx  := i.U
				}	
			}

			// No match is found and table is not full
			when (io.snoop.valid && !table_full && !entry_match) {
				// Incrememnt LRU entries
				for (i <- 0 until NUM_ENTRIES) {
					// Only increment valid entries
					when (i.U < curr_entry_num) {
						table_lru_state(i)  := table_lru_state(i) + 1.U
					}
				}

				// Write new entry
				table_missAddr  (curr_entry_num)    := io.snoop.bits.address
				table_write     (curr_entry_num)    := io.snoop.bits.write
				table_lru_state (curr_entry_num)    := 0.U

				// Initialize memory for the entry
				for (i <- 0 until NUM_PREDICTORS) {
					predictor_memBank(i).wrEna        := true.B
					predictor_memBank(i).wrIdx        := curr_entry_num
					predictor_memBank(i).wrAddr       := 0.U
					predictor_memBank(i).wrValidBit   := false.B
					predictor_memBank(i).wrConf       := 0.U
					predictor_memBank(i).wrWriteBit   := false.B

					// Set debug signal to indicate that a write happened
					write_valid(i) := true.B
				}

				// Update logic
				curr_entry_num          := curr_entry_num + 1.U
				reg_curr_snoop_match	:= false.B
				reg_curr_snoop_miss  	:= true.B
				reg_curr_snoop_idx      := curr_entry_num
			}

			// No match is found and the table is full
			.elsewhen (io.snoop.valid && table_full && !entry_match) {
				// Find oldest entry and increment entries
				for (i <- 0 until NUM_ENTRIES) {
					// The oldest entry must be the highest possible value
					when (table_lru_state(i) === MAX_ENTRY_INDEX.U) {
						oldest_entry        := i.U
					}

					// All other cases should just have their lru value incremented
					.otherwise {
						table_lru_state(i)  := table_lru_state(i) + 1.U
					}
				}

				// Replace old entry
				table_missAddr  (oldest_entry)    := io.snoop.bits.address
				table_write     (oldest_entry)    := io.snoop.bits.write
				table_lru_state (oldest_entry)    := 0.U

				// Initialize memory for the entry
				for (i <- 0 until NUM_PREDICTORS) {
					predictor_memBank(i).wrEna        := true.B
					predictor_memBank(i).wrIdx        := oldest_entry
					predictor_memBank(i).wrAddr       := 0.U
					predictor_memBank(i).wrValidBit   := false.B
					predictor_memBank(i).wrConf       := 0.U
					predictor_memBank(i).wrWriteBit   := false.B

					// Set debug signal to indicate that a write happened
					write_valid(i) := true.B
				}

				// Set signals for the match logic
				reg_curr_snoop_match	:= false.B
				reg_curr_snoop_miss		:= true.B
				reg_curr_snoop_idx      := oldest_entry
			}

			// A match is found
			.elsewhen (io.snoop.valid && entry_match) {
				// Update lru values
				for (i <- 0 until NUM_ENTRIES) {
					// Any lru value smaller than our match will be updated
					when ((table_lru_state(i) < table_lru_state(entry_match_idx)) && (i.U < curr_entry_num)) {
						table_lru_state(i)  := table_lru_state(i) + 1.U
					}
				}

				// Reset LRU for this match access
				table_lru_state(entry_match_idx) := 0.U

				// Read from all predictors to update them
				for (i <- 0 until NUM_PREDICTORS) {
					predictor_memBank(i).rdEna  := true.B
					predictor_memBank(i).rdIdx  := entry_match_idx

					// Set debug signal to indicate that a read happened
					read_valid(i)  := true.B
				}

				// Set signals for the match logic
				reg_curr_snoop_match	:= true.B
				reg_curr_snoop_miss  	:= false.B
				reg_curr_snoop_idx      := entry_match_idx
			}

			when (io.snoop.valid) {
				// Set the registers that hold the previous access
				for (i <- 0 until NUM_PREDICTORS) {
					reg_prev_addrDataMem 	:= reg_curr_addrDataMem         
					reg_prev_validMem       := reg_curr_validMem  
					reg_prev_writeMem       := reg_curr_writeMem      
					reg_prev_confMem        := reg_curr_confMem
					
					reg_prev_snoop_match	:= reg_curr_snoop_match
					reg_prev_snoop_miss 	:= reg_curr_snoop_miss
					reg_prev_snoop_idx  	:= reg_curr_snoop_idx
				}

				state	:= sReadPredictors
			}
		}

	// ===================================================================================

		// With a match found, we need to see if we have a match in our predictor
		is(sReadPredictors) {
			state_readPredictors 	:= true.B
			state   				:= sReadPredictors

			when (reg_curr_snoop_match) {
				// Store the first "NUM_REQUESTS" into our request wire to send to the queue
				for (i <- 0 until NUM_REQUESTS) {
					pipe_requests(i).valid		:= predictor_memBank(i).rdValidBit
					pipe_requests(i).address	:= predictor_memBank(i).rdAddr
					pipe_requests(i).write		:= predictor_memBank(i).rdWriteBit
				}
			

				// Store the memory output for later
				for (i <- 0 until NUM_PREDICTORS) {
					reg_curr_validMem(i)     := predictor_memBank(i).rdValidBit
					reg_curr_addrDataMem(i)  := predictor_memBank(i).rdAddr
					reg_curr_writeMem(i)     := predictor_memBank(i).rdWriteBit
					reg_curr_confMem(i)      := predictor_memBank(i).rdConf
				}
			}
			.otherwise {
				for (i <- 0 until NUM_PREDICTORS) {
					reg_curr_validMem(i)     := false.B
					reg_curr_addrDataMem(i)  := 0.U
					reg_curr_writeMem(i)     := false.B
					reg_curr_confMem(i)      := 0.U
				}
			}

			// Count valid entries
			// Invert bits and use PriorityEncoder to find the first 0
			val invValidVec     	= VecInit(reg_prev_validMem.map(!_))
			val firstInvalidIdx 	= PriorityEncoder(invValidVec)
			val allValid        	= reg_prev_validMem.reduce(_ && _)
			predictors_num_valid    := Mux(allValid, NUM_PREDICTORS.U, firstInvalidIdx)

			// See if a match exists and compute respective confidences
			val matchVec    		= WireDefault(VecInit(Seq.fill(NUM_PREDICTORS)(false.B)))
			val matchConfs  		= WireDefault(VecInit(Seq.fill(NUM_PREDICTORS)(0.U(BITSIZE_MAX_HISTORY.W)))) // confWidth = log2Ceil(MAX_HISTORY)

			when (reg_curr_snoop_match) {
				for (i <- 0 until NUM_PREDICTORS) {
					// Check validity and address/write bit match
					matchVec(i) := reg_prev_validMem(i) &&
									(reg_curr_snoop_addr === reg_prev_addrDataMem(i)) &&
									(reg_curr_snoop_write === reg_prev_writeMem(i))

					// Compute updated confidence
					matchConfs(i) := Mux(
						matchVec(i) && (reg_prev_confMem(i) < (MAX_HISTORY.U - 1.U)),
						reg_prev_confMem(i) + 1.U,
						reg_prev_confMem(i)
					)
				}
			

				// Find if a match exists and update the confidences
				predictor_matchExists      	:= matchVec.reduce(_ || _)
				predictor_matchExists_idx  	:= PriorityEncoder(matchVec)
				new_conf                  	:= matchConfs
			}

			when (matchVec.reduce(_ || _)) {
				state   := sNeedUpdate
			}
			.otherwise {
				state   := sNewPredictor
			}

			invValidVec_debug       	:= invValidVec
			firstInvalidIdx_debug   	:= firstInvalidIdx
			allValid_debug          	:= allValid
			matchVec_debug          	:= matchVec
			matchConfs_debug        	:= matchConfs
		}

	// ===================================================================================

		is (sNewPredictor) {
			state_newPredictor	:= true.B
			state				:= sIdle

			when (predictors_full) {
				predictor_memBank(NUM_PREDICTORS - 1).wrEna      	:= true.B
				predictor_memBank(NUM_PREDICTORS - 1).wrIdx      	:= reg_prev_snoop_idx
				predictor_memBank(NUM_PREDICTORS - 1).wrAddr     	:= reg_curr_snoop_addr
				predictor_memBank(NUM_PREDICTORS - 1).wrValidBit 	:= true.B
				predictor_memBank(NUM_PREDICTORS - 1).wrConf     	:= 0.U
				predictor_memBank(NUM_PREDICTORS - 1).wrWriteBit 	:= reg_curr_snoop_write

				write_valid(NUM_PREDICTORS - 1)     		:= true.B
			}
			// Fill next avaiable entry
			.otherwise {
				predictor_memBank(predictors_num_valid).wrEna      	:= true.B
				predictor_memBank(predictors_num_valid).wrIdx      	:= reg_prev_snoop_idx
				predictor_memBank(predictors_num_valid).wrAddr     	:= reg_curr_snoop_addr
				predictor_memBank(predictors_num_valid).wrValidBit 	:= true.B
				predictor_memBank(predictors_num_valid).wrConf     	:= 0.U
				predictor_memBank(predictors_num_valid).wrWriteBit 	:= reg_curr_snoop_write

				write_valid(predictors_num_valid)			:= true.B
			}
		}

	// ===================================================================================

		// Write updated values
		is (sNeedUpdate) {
			state_needUpdate	:= true.B

			// Find which index the changed predictor will move to if any at all
			for (i <- 0 until NUM_PREDICTORS) {
				// Logic to see if a valid move exists and what idx. This is ordered to go from end to beginning
				conf_dif(i) := new_conf(predictor_matchExists_idx).asSInt - reg_prev_confMem(i).asSInt
				when ((conf_dif(i) > 0.S) && (i.U <= predictor_matchExists_idx)) {
					move_valid_vector(i) := true.B
					move_valid := true.B
				}
				.otherwise {
					move_valid_vector(i) := false.B
				}
			}

			when (move_valid) {
				state	:= sUpdatePredictors
			}
			.otherwise {
				state	:= sIdle
			}
		}

	// ===================================================================================

		// Write updated values
		is (sUpdatePredictors) {
			state_updatePredictors	:= true.B

			// Shift logic for when a valid move is available
			// Identify move index
			when (move_valid_vector(0)) {
				move_idx := 0.U
			}
			.otherwise {
				for (i <- 1 until NUM_PREDICTORS) {
					when ((move_valid_vector(i)) && (!move_valid_vector(i - 1))) {
					move_idx := i.U
					}
				}
			}

			for (i <- 1 to NUM_PREDICTORS) {
				// Check if current predictor is a candidate for switching and is before our desination
				when (((NUM_PREDICTORS.U - i.U) <= predictor_matchExists_idx)  &&  ((NUM_PREDICTORS.U - i.U) > move_idx)) {
					// Shift entry back
					predictor_memBank(NUM_PREDICTORS.U - i.U).wrEna       := true.B
					predictor_memBank(NUM_PREDICTORS.U - i.U).wrIdx       := reg_prev_snoop_idx
					predictor_memBank(NUM_PREDICTORS.U - i.U).wrAddr      := reg_prev_addrDataMem  	(NUM_PREDICTORS.U - i.U - 1.U)
					predictor_memBank(NUM_PREDICTORS.U - i.U).wrValidBit  := reg_prev_validMem     	(NUM_PREDICTORS.U - i.U - 1.U)
					predictor_memBank(NUM_PREDICTORS.U - i.U).wrConf      := new_conf      			(NUM_PREDICTORS.U - i.U - 1.U)
					predictor_memBank(NUM_PREDICTORS.U - i.U).wrWriteBit  := reg_prev_writeMem     	(NUM_PREDICTORS.U - i.U - 1.U)

					// Set debug value for writing to a match bank
					write_valid(NUM_PREDICTORS.U - i.U) := true.B
				}
			}

			// Move matching entry to the move idx
			predictor_memBank(move_idx).wrEna       := true.B
			predictor_memBank(move_idx).wrIdx       := reg_prev_snoop_idx
			predictor_memBank(move_idx).wrAddr      := reg_prev_addrDataMem (predictor_matchExists_idx)
			predictor_memBank(move_idx).wrValidBit  := reg_prev_validMem    (predictor_matchExists_idx)
			predictor_memBank(move_idx).wrConf      := new_conf         	(predictor_matchExists_idx)
			predictor_memBank(move_idx).wrWriteBit  := reg_prev_writeMem    (predictor_matchExists_idx)

			// Set debug value for writing to a match bank
			write_valid(move_idx) 	:= true.B
			state   				:= sIdle
		}
	}

	// ==================================================================================================================
	// Output logic

	io.request.valid			:= Mux(q.io.deq.valid, q.io.deq.bits.valid, false.B)
	io.request.bits.address		:= Mux(q.io.deq.valid, q.io.deq.bits.address, 0.U)
	io.request.bits.write		:= Mux(q.io.deq.valid, q.io.deq.bits.write, false.B)
	
	//          CHISEL DEBUG
	// ==================================================================================================================
	when (params.log.U > 0.U) {
		when (q.io.deq.valid) {
			printf("MARKOV_OUT:\t\t\t\tout_address = %x - out_write = %x\n", q.io.deq.bits.address, q.io.deq.bits.write)
		}
	}
	
	// val counter = RegInit(0.U(64.W))
	// counter 	:= Mux(io.snoop.valid, counter + 1.U, counter)
	// chisel3.dontTouch(counter)

	// when(io.snoop.valid) {
    //     printf("SNOOP NUMBER = %d - address = %x, write = %d, match = %d, match_idx = %d, curr_idx = %d, prev_idx = %d, curr_entries = %d, oldest_entry = %d\n", counter, io.snoop.bits.address, io.snoop.bits.write, entry_match, entry_match_idx, reg_curr_snoop_idx, reg_prev_snoop_idx, curr_entry_num, oldest_entry)
    // }

	// =============================================================================================================
	chisel3.dontTouch(table_missAddr)
	chisel3.dontTouch(table_write)
	chisel3.dontTouch(table_lru_state)

	chisel3.dontTouch(table_full)
	chisel3.dontTouch(oldest_entry)
	chisel3.dontTouch(curr_entry_num)

	chisel3.dontTouch(entry_match)
	chisel3.dontTouch(entry_match_idx)

	chisel3.dontTouch(reg_prev_snoop_match)
	chisel3.dontTouch(reg_prev_snoop_miss)
	chisel3.dontTouch(reg_prev_snoop_idx)

	chisel3.dontTouch(reg_curr_snoop_match)
	chisel3.dontTouch(reg_curr_snoop_miss)
	chisel3.dontTouch(reg_curr_snoop_idx)

	chisel3.dontTouch(reg_curr_snoop_addr)
	chisel3.dontTouch(reg_curr_snoop_write)

	chisel3.dontTouch(reg_prev_addrDataMem)
	chisel3.dontTouch(reg_prev_validMem)
	chisel3.dontTouch(reg_prev_writeMem)
	chisel3.dontTouch(reg_prev_confMem)

	chisel3.dontTouch(reg_curr_addrDataMem)
	chisel3.dontTouch(reg_curr_validMem)
	chisel3.dontTouch(reg_curr_writeMem)
	chisel3.dontTouch(reg_curr_confMem)

	chisel3.dontTouch(predictor_memBank)

	chisel3.dontTouch(predictor_matchExists)
	chisel3.dontTouch(predictor_matchExists_idx)

	// chisel3.dontTouch(prefetch_requests)

	chisel3.dontTouch(state)

	chisel3.dontTouch(move_valid_vector)
	chisel3.dontTouch(move_valid)
	chisel3.dontTouch(move_idx)

	chisel3.dontTouch(predictors_num_valid)
	chisel3.dontTouch(predictors_full)

	chisel3.dontTouch(new_conf)
	chisel3.dontTouch(conf_dif)

	chisel3.dontTouch(read_valid)
	chisel3.dontTouch(write_valid)

	chisel3.dontTouch(invValidVec_debug)
	chisel3.dontTouch(firstInvalidIdx_debug)
	chisel3.dontTouch(allValid_debug)
	chisel3.dontTouch(matchVec_debug)
	chisel3.dontTouch(matchConfs_debug)

	chisel3.dontTouch(state_idle)
	chisel3.dontTouch(state_readPredictors)
	chisel3.dontTouch(state_needUpdate)
	chisel3.dontTouch(state_newPredictor)
	chisel3.dontTouch(state_updatePredictors)
}


// ==================================================================================================================


//                      HELP CLASSES / BUNDLES
// ==================================================================================================================

class Prefetch_Entry_Staged(addrsize: Int) extends Bundle {
	val valid       = Bool()
	val address		= UInt(addrsize.W)
	val write       = Bool()
}

// ===============================================================

class Table_Mem_Staged(addrsize: Int, tablesize: Int, datasize: Int, confsize: Int) extends Module {
	val io = IO(new Bundle {

		val rdEna      	= Input(Bool ())
		val rdIdx      	= Input(UInt (addrsize.W))
		val rdAddr     	= Output(UInt (datasize.W))
		val rdValidBit 	= Output(Bool ())
		val rdConf     	= Output(UInt (confsize.W))
		val rdWriteBit 	= Output(Bool ())
	
		val wrEna      	= Input(Bool ())
		val wrIdx      	= Input(UInt (addrsize.W))
		val wrAddr     	= Input(UInt (datasize.W))
		val wrValidBit 	= Input(Bool ())
		val wrConf     	= Input(UInt (confsize.W))
		val wrWriteBit 	= Input(Bool ())
	})

	val maxBankBits 	= 32 * 1024 // 32Kb = 32 * 1024 bits
	val totalBits 		= tablesize.toLong * 32 // datasize
	val numBanks 		= ((totalBits + maxBankBits - 1) / maxBankBits).toInt
	val entriesPerBank 	= ((tablesize + numBanks - 1) / numBanks).toInt


	val addrMemBanks = Seq.fill(numBanks) {
		SyncReadMem(entriesPerBank, UInt(datasize.W))
	}

	println("[INFO] Generated Table_Mem Configuration:")
	println(s"[INFO]   tablesize     = $tablesize")
	println(s"[INFO]   datasize      = $datasize")
	println(s"[INFO]   confsize      = $confsize")
	println(s"[INFO]   totalBits     = $totalBits bits")
	println(s"[INFO]   maxBankBits   = $maxBankBits bits per bank")
	println(s"[INFO]   numBanks      = $numBanks banks")
	println(s"[INFO]   entriesPerBank= $entriesPerBank entries per bank")


	def getBankInfo(idx: UInt): (UInt, UInt) = {
		if (numBanks == 1) {
			(0.U, idx)
		} 
		else {
			val bankBits = log2Ceil(numBanks)
			val bankIdx = idx(bankBits-1, 0)
			val localIdx = idx >> bankBits
			(bankIdx, localIdx)
		}
	}


	val addrMem             = SyncReadMem(tablesize, UInt(32.W))
	val addrWriteReg     	= RegNext(io.wrAddr)
	val addrWriteReg2     	= RegNext(addrWriteReg)
	val addrRead         	= WireDefault(0.U(32.W))
	val addrMux				= WireDefault(0.U(32.W))
	
	val validBitMem         = SyncReadMem(tablesize, Bool())
	val validBitWriteReg 	= RegNext(io.wrValidBit)
	val validBitWriteReg2 	= RegNext(validBitWriteReg)
	val validBitRead     	= WireDefault(false.B)
	val validBitMux			= WireDefault(false.B)

	val confMem             = SyncReadMem(tablesize, UInt(confsize.W))
	val confWriteReg     	= RegNext(io.wrConf)
	val confWriteReg2     	= RegNext(confWriteReg)
	val confRead         	= WireDefault(0.U(confsize.W))
	val confMux				= WireDefault(0.U(confsize.W))

	val writeBitMem         = SyncReadMem(tablesize, Bool())
	val writeBitWriteReg 	= RegNext(io.wrWriteBit)
	val writeBitWriteReg2 	= RegNext(writeBitWriteReg)
	val writeBitRead     	= WireDefault(false.B)
	val writeBitMux			= WireDefault(false.B)

	val doForwardReg     	= RegNext((io.wrIdx === io.rdIdx) && io.wrEna)
	val doForwardReg2		= RegNext(doForwardReg)



	val (rdBankIdx, rdLocalIdx) = getBankInfo(io.rdIdx)
	val readResults = addrMemBanks.zipWithIndex.map { case (bank, i) =>
		val isThisBank = rdBankIdx === i.U
		bank.read(rdLocalIdx, io.rdEna && isThisBank)
	}
	val selVec = UIntToOH(rdBankIdx, numBanks)
	val sel = RegNext(VecInit(selVec.asBools))
	addrRead := Mux1H(sel, readResults)


	val rdEnaReg = RegNext(io.rdEna, false.B)

	// addrRead       := addrMem.read       (io.rdIdx,   io.rdEna)
	validBitRead   := validBitMem.read   (io.rdIdx,   io.rdEna) && rdEnaReg 
	confRead       := confMem.read       (io.rdIdx,   io.rdEna)
	writeBitRead   := writeBitMem.read   (io.rdIdx,   io.rdEna)

	when (io.wrEna) {
		val (wrBankIdx, wrLocalIdx) = getBankInfo(io.wrIdx)
		for ((bank, i) <- addrMemBanks.zipWithIndex) {
			when(wrBankIdx === i.U) {
				bank.write(wrLocalIdx, io.wrAddr)
			}
		}
	}

	// Write logic
	when(io.wrEna) {
		// addrMem.write       (io.wrIdx, io.wrAddr)
		validBitMem.write   (io.wrIdx, io.wrValidBit)
		confMem.write       (io.wrIdx, io.wrConf)
		writeBitMem.write   (io.wrIdx, io.wrWriteBit)
	}

	// IO Muxes
	addrMux        	:= Mux(doForwardReg2,   addrWriteReg,      addrRead)
	validBitMux    	:= Mux(doForwardReg2,   validBitWriteReg,  validBitRead)
	confMux        	:= Mux(doForwardReg2,   confWriteReg,      confRead)
	writeBitMux    	:= Mux(doForwardReg2,   writeBitWriteReg,  writeBitRead)

	io.rdAddr 		:= Cat(0.U((datasize - 32).W), addrMux)
	io.rdValidBit	:= validBitMux
	io.rdConf		:= confMux
	io.rdWriteBit	:= writeBitMux

	// Debug signals
	chisel3.dontTouch(addrWriteReg)
	chisel3.dontTouch(addrWriteReg2)
	chisel3.dontTouch(addrRead)
	chisel3.dontTouch(addrMux)

	chisel3.dontTouch(validBitWriteReg)
	chisel3.dontTouch(validBitWriteReg2)
	chisel3.dontTouch(validBitRead)
	chisel3.dontTouch(validBitMux)

	chisel3.dontTouch(confWriteReg)
	chisel3.dontTouch(confWriteReg2)
	chisel3.dontTouch(confRead)
	chisel3.dontTouch(confMux)

	chisel3.dontTouch(writeBitWriteReg)
	chisel3.dontTouch(writeBitWriteReg2)
	chisel3.dontTouch(writeBitRead)
	chisel3.dontTouch(writeBitMux)

	chisel3.dontTouch(rdEnaReg)

	chisel3.dontTouch(doForwardReg)
	chisel3.dontTouch(doForwardReg2)
}

// ==================================================================================================================
// end