package barf

import chisel3._

class PrefetchAccuracyUnit extends Module {
  val io = IO(new Bundle {
      val rst = Input(Bool())
      val prefetch = Input(Bool())
      val access = Input(Bool())
      val currentAccess = Input(UInt(64.W))
      val totalPrefetch = Output(UInt(32.W))
      val hitPrefetch = Output(UInt(32.W))
  })

  val totalCount = RegInit(0.U(32.W)) // Total prefetch count
  val hitCount = RegInit(0.U(32.W)) // Prefetch hits

  val prefetchBuffer = Reg(Vec(16, UInt(64.W))) // Buffer that stores the last 16 prefetched addresses.
  val bufferMetaData = RegInit(VecInit(Seq.fill(16)(false.B))) // Meta Data to see if each address is valid or not
  val nextSlot     = RegInit(0.U(4.W)) // Location that a new refetch address will be put

  when (io.rst) {
    totalCount := 0.U
    hitCount := 0.U
    nextSlot := 0.U
    bufferMetaData.foreach(_ := false.B)
  } .otherwise {
    when (io.prefetch) {
      totalCount := totalCount + 1.U
      prefetchBuffer(nextSlot) := io.currentAccess
      bufferMetaData(nextSlot) := true.B
      nextSlot := Mux(nextSlot === 15.U, 0.U, nextSlot + 1.U) // If nextSlot = 15, then go back to 0, else +1
    }
    when (io.access) {
     // When memory is accessed, check if that address has been prefetched or not.
     // First check all prefetched addresses in Buffer
      val matches = (0 until 16).map { i =>
        bufferMetaData(i) && (prefetchBuffer(i) === io.currentAccess)
      }

      when (matches.reduce(_ || _)) {
        hitCount := hitCount + 1.U
      }
    }
  }

  io.totalPrefetch := totalCount
  io.hitPrefetch := hitCount
}

