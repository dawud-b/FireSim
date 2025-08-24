package barf

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.subsystem.{CacheBlockBytes}

case class NLPrefetcherParams() extends CanInstantiatePrefetcher {
  def desc() = "Custom Next-Line Prefetcher"
  def instantiate()(implicit p: Parameters) = Module(new NLPrefetcher(this)(p))
}

class NLPrefetcher(params: NLPrefetcherParams)(implicit p: Parameters) extends AbstractPrefetcher()(p) {
  // things I need to set
  val valid = RegInit(false.B) 
  val write = RegInit(false.B)
  val address = RegInit(0.U(40.W))

  valid :=  io.snoop.valid
  write :=  io.snoop.bits.write
  address :=  io.snoop.bits.address + io.request.bits.blockBytes.U    

  io.request.valid := valid
  io.request.bits.write := write
  io.request.bits.address := address

  // ---- Prefetch Accuracy Tracker ----
  val accuracyTracker = Module(new barf.PrefetchAccuracyUnit)
  accuracyTracker.io.rst := false.B
  accuracyTracker.io.prefetch := io.request.valid
  accuracyTracker.io.access := io.snoop.valid
  accuracyTracker.io.currentAccess := Mux(io.request.valid, io.request.bits.address, io.snoop.bits.address)


  // wszogg start
  when (valid) {
    printf("NL_CUSTOM_OUT:\t\t\tout_address = %x - out_write = %x\n", address, write)
    // accuracy print
    printf("PREFETCH_STATS: total=%d, hits=%d\n", accuracyTracker.io.totalPrefetch, accuracyTracker.io.hitPrefetch)
  }

  // end
}
