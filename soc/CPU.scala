package ysyx

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

object CPUAXI4BundleParameters {
  def apply() = AXI4BundleParameters(addrBits = 32, dataBits = 64, idBits = ChipLinkParam.idBits)
}

class ysyx_23060110 extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val io_interrupt = Input(Bool())
    val io_inst = Output(UInt(32.W))
    val io_pc = Output(UInt(64.W))
    val io_npc = Output(UInt(64.W))
    val io_master = AXI4Bundle(CPUAXI4BundleParameters())
    val io_slave = Flipped(AXI4Bundle(CPUAXI4BundleParameters()))
  })
}

class CPU(idBits: Int)(implicit p: Parameters) extends LazyModule {
  val masterNode = AXI4MasterNode(p(ExtIn).map(params =>
    AXI4MasterPortParameters(
      masters = Seq(AXI4MasterParameters(
        name = "cpu",
        id   = IdRange(0, 1 << idBits))))).toSeq)
  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (master, _) = masterNode.out(0)
    val interrupt = IO(Input(Bool()))
    val slave = IO(Flipped(AXI4Bundle(CPUAXI4BundleParameters())))
    val io_inst = IO(Output(UInt(32.W)))
    val io_pc = IO(Output(UInt(64.W)))
    val io_npc = IO(Output(UInt(64.W)))


    val cpu = Module(new ysyx_23060110)
    cpu.io.clock := clock
    cpu.io.reset := reset
    cpu.io.io_interrupt := interrupt
    cpu.io.io_slave <> slave
    io_inst := cpu.io.io_inst
    io_pc := cpu.io.io_pc 
    io_npc := cpu.io.io_npc
    master <> cpu.io.io_master
  }
}
