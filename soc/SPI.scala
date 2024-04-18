package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class SPIIO(val ssWidth: Int = 8) extends Bundle {
  val sck = Output(Bool())
  val ss = Output(UInt(ssWidth.W))
  val mosi = Output(Bool())
  val miso = Input(Bool())
}

class spi_top_apb extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val spi = new SPIIO
    val spi_irq_out = Output(Bool())
  })
}

class flash extends BlackBox {
  val io = IO(Flipped(new SPIIO(1)))
}

class APBSPI(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
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
    val spi_bundle = IO(new SPIIO)

    val mspi = Module(new spi_top_apb)
    mspi.io.clock := clock
    mspi.io.reset := reset
    spi_bundle <> mspi.io.spi

    val s_idle :: s_init_div :: s_init_ss :: s_init_ctr :: s_send_dat :: s_set_go :: s_wait_read :: s_read :: s_rec_ss :: s_finished :: Nil = Enum(10)
    // 对 spi 进行初始化操作，要依次写入除数，片选，控制信号，随后写入数据，再读出数据，恢复片选
    val state = RegInit(s_idle)
    
    val r_paddr           = WireInit(0.U(32.W))
    val r_psel            = RegInit(0.B)
    val r_penable         = RegInit(0.B)
    val r_pprot           = RegInit(1.U(3.W))
    val r_pwrite          = WireInit(0.B)
    val r_pwdata          = WireInit(0.U(32.W))
    val r_pstrb           = RegInit(15.U(4.W))
    val r_pready          = WireInit(0.B)
    val r_prdata          = RegInit(0.U(32.W))
    val r_pslverr         = RegInit(0.B)
    val r_o_pready        = WireInit(0.B)
    val r_o_prdata        = RegInit(0.U(32.W))
    val r_o_pslverr       = RegInit(0.B)

    mspi.io.in.paddr      := r_paddr
    mspi.io.in.psel       := r_psel
    mspi.io.in.penable    := r_penable
    mspi.io.in.pprot      := r_pprot
    mspi.io.in.pwrite     := r_pwrite 
    mspi.io.in.pwdata     := r_pwdata 
    mspi.io.in.pstrb      := r_pstrb  
    r_pready              := mspi.io.in.pready 
    r_prdata              := mspi.io.in.prdata 
    r_pslverr             := mspi.io.in.pslverr
    in.pready             := r_o_pready
    in.prdata             := r_o_prdata
    in.pslverr            := r_o_pslverr


    when (in.paddr >= "x30000000".U && in.paddr <= "x3fffffff".U) {
      switch(state) {
        is(s_idle) {
          r_o_pready := 0.U
          when (in.psel & in.penable) {
            state := s_init_div
          }
        }
        is(s_init_div) {
          // r_o_pready     := 0.B
          r_paddr        := "x10001014".U 
          r_pwdata       := 0.U  // 除数设为 0
          r_pwrite       := 1.B
          r_psel         := 1.B 
          r_pprot        := 0.U
          r_pstrb        := 15.U  // 32 位
          when (r_pready) {
            r_penable := 0.B 
            r_psel := 0.B
            state := s_init_ss
          } .otherwise {
            r_penable := RegNext(r_psel)
          }
        }
        is(s_init_ss) {
          r_paddr        := "x10001018".U 
          r_pwdata       := 1.U   //片选选1 flash
          r_pstrb        := 1.U
          r_pwrite       := 1.B
          r_psel := 1.B
          when (r_pready) {
            r_penable := 0.B 
            r_psel := 0.B
            state := s_init_ctr
          }.otherwise{
            r_penable := RegNext(r_psel)
          }
        }
        is(s_init_ctr) {
          r_paddr         := "x10001010".U
          r_pwdata       := "b1100001000000".U
          r_pwrite       := 1.B
          r_pstrb        := 3.U
          r_psel := 1.B
          when (r_pready) {
            r_penable := 0.B
            r_psel := 0.B
            state := s_send_dat
          } .otherwise {
            r_penable := RegNext(r_psel)
          }
        }
        is(s_send_dat) {
          r_paddr         := "x10001000".U
          r_pwdata       := "b11000000".U + (Reverse(in.paddr(23, 0)) << 8.U)
          // printf("spi xip in paddr=%x revpaddr=%x\n", in.paddr(23, 0), Reverse(in.paddr(23, 0)))
          r_psel    := 1.B
          r_pwrite := 1.B
          r_pstrb := 15.U
          when (r_pready) {
            r_penable := 0.B
            r_psel := 0.B
            state := s_set_go
          } .otherwise {
            r_penable := RegNext(r_psel)
          }
        }
        is(s_set_go) {
          r_paddr         := "x10001011".U
          r_pwdata       := "b1100101000000".U
          r_pstrb        := 2.U
          r_psel := 1.B
          r_pwrite := 1.B
          when (r_pready) {
            r_penable := 0.B
            r_psel := 0.B
            state := s_wait_read
          }.otherwise {
            r_penable := RegNext(r_psel)
          }
        }
        is(s_wait_read){
          r_paddr         := "x10001011".U
          r_pwrite       := 0.B
          r_psel         := 1.B
          when (r_pready && !(mspi.io.in.prdata & "b100000000".U)) {  // 完成写入后，go 位被置为 0
            r_penable := 0.B
            r_psel := 0.B
            state := s_read
          }.otherwise {
            r_penable := RegNext(r_psel)
          }
        }
        is(s_read) {
          r_paddr         := "x10001004".U
          r_pwrite       := 0.B
          r_psel := 1.B
          when (r_pready) {
            r_penable    := 0.B
            r_psel       := 0.B
            // r_o_prdata   := r_prdata(31, 24) + (r_prdata(23, 16) << 8.U) + (r_prdata(15, 8) << 16.U) + (r_prdata(7, 0) << 24.U)
            r_o_prdata := Reverse(r_prdata(7, 0)) + (Reverse(r_prdata(15, 8)) << 8.U) + (Reverse(r_prdata(23, 16)) << 16.U) + (Reverse(r_prdata(31, 24)) << 24.U)
            state := s_rec_ss
          }.otherwise {
            r_penable := RegNext(r_psel)
          }
        }
        is(s_rec_ss) {
          r_psel := 1.B
          r_paddr         := "x10001018".U
          r_pwrite       := 1.B
          r_pwdata       := 0.U
          r_pstrb        := 1.B
          
          when (r_pready) {
            r_penable := 0.B
            
            r_psel := 0.B
            state := s_finished
            
          }.otherwise{
            r_penable := RegNext(r_psel)
          }
        }
        is(s_finished){
          r_psel := 0.B 
          r_paddr := 0.U
          r_pwrite := 0.B
          r_o_pready := 1.B   // 输出
          when (!in.psel && !in.penable) {
            state := s_idle
          }
        }
      }
    }.elsewhen(in.paddr >= "x10001000".U && in.paddr <= "x10001fff".U){
      mspi.io.in <> in
    }.otherwise{
      in.pslverr := 1.B
    }
    spi_bundle <> mspi.io.spi
  }
}
