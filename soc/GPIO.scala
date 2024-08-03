package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class GPIOIO extends Bundle {
  val out = Output(UInt(16.W))
  val in = Input(UInt(16.W))
  val seg = Output(Vec(8, UInt(8.W)))
}

class GPIOCtrlIO extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Reset())
  val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
  val gpio = new GPIOIO
}

class gpio_top_apb extends BlackBox {
  val io = IO(new GPIOCtrlIO)
}

object SegToHex7 {
  def apply(seg: UInt) = {
    val s = new SegToHex7(seg)
    s.data
  }
}

class SegToHex7 (seg: UInt) {
    val data = MuxLookup(seg, 0.U)(Seq(
      "b0000001".U -> 0.U,
      "b1001111".U -> 1.U,
      "b0010010".U -> 2.U,
      "b0000110".U -> 3.U,
      "b1001100".U -> 4.U,
      "b0100100".U -> 5.U,
      "b0100000".U -> 6.U,
      "b0001111".U -> 7.U,
      "b0000000".U -> 8.U,
      "b0000100".U -> 9.U,
      "b0001000".U -> 10.U,
      "b1100000".U -> 11.U,
      "b0110001".U -> 12.U,
      "b1000010".U -> 13.U,
      "b0110000".U -> 14.U,
      "b0111000".U -> 15.U
    ))
}

class gpioChisel extends Module {
  val io              = IO(new GPIOCtrlIO)
  val gpio_led        = Reg(UInt(16.W))
  val gpio_switch     = WireInit(0.U(16.W))
  val gpio_seg        = RegInit(VecInit(Seq.fill(8)(0.U(8.W))))

  gpio_switch := io.gpio.in
  io.gpio.out := gpio_led
  io.gpio.seg <> gpio_seg
  val GPIO_BASE = 0x1000_2000L
  io.in.pready := io.in.psel & io.in.penable
  io.in.pslverr := 0.B
  io.in.prdata := Mux((io.in.psel), MuxLookup(io.in.paddr, 0.U)(Seq(
    GPIO_BASE.U       -> Cat(0.U(16.W), gpio_led),
    (GPIO_BASE + 4).U -> Cat(0.U(16.W), gpio_switch)
    // (GPIO_BASE + 8).U -> gpio_seg.flatMap(_ => SegToHex7(_)).reduce(Cat(_, _))
  )), 0.U)

  when(io.in.pwrite) {
    when (io.in.paddr === GPIO_BASE.U) {
      // 这里没有管 pstrb
      gpio_led := io.in.pwdata
    }
  }
}

class APBGPIO(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val node = APBSlaveNode(Seq(APBSlavePortParameters(
    Seq(APBSlaveParameters(
      address       = address,
      executable    = true,
      supportsRead  = true,
      supportsWrite = true)),
    beatBytes  = 4)))

  private val outer = this

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val gpio_bundle = IO(new GPIOIO)

    val mgpio = Module(new gpioChisel)
    mgpio.io.clock := clock
    mgpio.io.reset := reset
    mgpio.io.in <> in
    gpio_bundle <> mgpio.io.gpio
  }
}
