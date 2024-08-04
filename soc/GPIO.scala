package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._


object Seg7ToHex {
  def apply(seg: UInt) = {
    val s = new Seg7ToHex(seg)
    s.data
  }
}

class Seg7ToHex (seg: UInt) {
    val data = MuxLookup(seg, 0.U)(Seq(
      "b00000010".U -> RegInit(0.U(4.W)),
      "b10011111".U -> RegInit(1.U(4.W)),
      "b00100101".U -> RegInit(2.U(4.W)),
      "b00001101".U -> RegInit(3.U(4.W)),
      "b10011001".U -> RegInit(4.U(4.W)),
      "b01001001".U -> RegInit(5.U(4.W)),
      "b01000001".U -> RegInit(6.U(4.W)),
      "b00011111".U -> RegInit(7.U(4.W)),
      "b00000001".U -> RegInit(8.U(4.W)),
      "b00001001".U -> RegInit(9.U(4.W)),
      "b00010001".U -> RegInit(10.U(4.W)),
      "b11000001".U -> RegInit(11.U(4.W)),
      "b01100011".U -> RegInit(12.U(4.W)),
      "b10000101".U -> RegInit(13.U(4.W)),
      "b01100001".U -> RegInit(14.U(4.W)),
      "b01110001".U -> RegInit(15.U(4.W))
    ))
}

object HexToSeg7 {
  def apply(hex: UInt) = {
    val s = new HexToSeg7(hex)
    s.data
  }
}

class HexToSeg7(hex: UInt) {
  val data = MuxLookup(hex, 0.U)(Seq(
    0.U   -> RegInit("b00000010".U(8.W)),
    1.U   -> RegInit("b10011111".U(8.W)),
    2.U   -> RegInit("b00100101".U(8.W)),
    3.U   -> RegInit("b00001101".U(8.W)),
    4.U   -> RegInit("b10011001".U(8.W)),
    5.U   -> RegInit("b01001001".U(8.W)),
    6.U   -> RegInit("b01000001".U(8.W)),
    7.U   -> RegInit("b00011111".U(8.W)),
    8.U   -> RegInit("b00000001".U(8.W)),
    9.U   -> RegInit("b00001001".U(8.W)),
    10.U  -> RegInit("b00010001".U(8.W)),
    11.U  -> RegInit("b11000001".U(8.W)),
    12.U  -> RegInit("b01100011".U(8.W)),
    13.U  -> RegInit("b10000101".U(8.W)),
    14.U  -> RegInit("b01100001".U(8.W)),
    15.U  -> RegInit("b01110001".U(8.W))
  ))
}
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
  )), 0.U)

  when(io.in.pwrite & io.in.pready) {
    when (io.in.paddr === GPIO_BASE.U) {
      // 这里没有管 pstrb
      gpio_led := io.in.pwdata
    }.elsewhen (io.in.paddr === (GPIO_BASE+8).U) {
      printf("write seg: wdata := %d = %x\n", io.in.pwdata, io.in.pwdata);
      gpio_seg := gpio_seg.zipWithIndex.map {
        case(t, i) => (HexToSeg7(io.in.pwdata(i*4+3, i*4)))
      }
    }.otherwise {
      printf("gpio write error addr = %x\n", io.in.paddr)
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
