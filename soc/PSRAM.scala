package ysyx

import chisel3._
import chisel3.util._
import chisel3.experimental.Analog

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class QSPIIO extends Bundle {
  val sck = Output(Bool())
  val ce_n = Output(Bool())
  val dio = Analog(4.W)
}

class psram_top_apb extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val qspi = new QSPIIO
  })
}

class psram extends BlackBox {
  val io = IO(Flipped(new QSPIIO))
}

class psramChisel extends Module {
  // 原先是RawModule,这里改成Module了，并且将默认时钟作为 psram dpic 的时钟了
  val io = IO(Flipped(new QSPIIO))

  val ren = WireInit(0.B)
  val dread = Wire(UInt(4.W))
  val di = TriStateInBuf(io.dio, dread, !ren) // change this if you need -> changed
  val psram_cmd_io = Module(new psram_cmd)
  val inner_clk = io.sck

  psram_cmd_io.io.bready := io.ce_n
  val next_io_ce_n = RegNext(io.ce_n)
  val next_io_ce_n2 = RegNext(next_io_ce_n)

  withClockAndReset(inner_clk.asClock, io.ce_n.asBool) {
    val QPI = RegInit(0.B)
    val data_counter = RegInit(0.U(8.W))
    val data_bswap = Wire(UInt(32.W))
    val cmd_reg = RegInit(0.U(8.W))
    val addr_reg = RegInit(0.U(24.W))
    val data_reg = RegInit(0.U(32.W))
    val idata_reg = RegInit(0.U(32.W))
    val counter = RegInit(0.U(8.W))
    
    data_bswap := MuxLookup(data_counter, 0.U)(Seq(
      28.U -> Cat(idata_reg(7, 0), idata_reg(15, 8), idata_reg(23, 16), idata_reg(31, 24)),
      12.U -> Cat(0.U(16.W), idata_reg(7, 0), idata_reg(15, 8)),
      4.U  -> Cat(0.U(24.W), idata_reg(7, 0))
    ))
  
    val cmd_t :: addr_t :: waitr_t :: r_data_t :: w_data_t :: write_done_t :: err_t :: Nil = Enum(7)
    val state_t = RegInit(cmd_t)
    psram_cmd_io.io.clk := clock
    psram_cmd_io.io.rst := reset
    psram_cmd_io.io.rvalid := (state_t === waitr_t) && (counter === 5.U) && (cmd_reg === "xEB".U) // 只会生效一个周期
    psram_cmd_io.io.wvalid := (cmd_reg === "x38".U) && io.ce_n && !next_io_ce_n
    psram_cmd_io.io.wmask := MuxLookup(data_counter, 7.U)(Seq(
        28.U -> 15.U,
        12.U -> 3.U,
        4.U -> 1.U
      ))
      //data_counter >> 3.U;

    psram_cmd_io.io.addr := addr_reg

    dread := data_reg(31, 28)
    psram_cmd_io.io.idata := data_bswap
    switch(state_t) {
      is(cmd_t) {
        when(counter === Mux(QPI, 2.U, 8.U)) {
          state_t := addr_t
          counter := 0.U
          addr_reg := Cat(addr_reg(19, 0), di)
          when(cmd_reg === "x35".U) { // 进入 QPI 模式
            QPI := 1.B
          }.elsewhen(cmd_reg === "xf5".U){
            QPI := 0.B
          }
        }.otherwise {
          counter := counter + 1.U
          cmd_reg := Mux(QPI, Cat(cmd_reg(3, 0), di), Cat(cmd_reg(6, 0), di(0)))
        }
      }
      is(addr_t) {
        when(counter === 20.U) {
          when (cmd_reg === "xEB".U) {
            state_t := waitr_t
            counter := 0.U
          }.elsewhen(cmd_reg === "x38".U) {
            state_t := w_data_t
            data_counter := 0.U
            idata_reg := di
            counter := 0.U
            ren := 1.B
          }.otherwise {
            state_t := err_t
          }
          counter := 0.U
          // addr_reg := Cat(0.U(2.W), addr_reg(23, 2))
        }.otherwise {
          counter := counter + 4.U
          addr_reg := Cat(addr_reg(19, 0), di)
        }
      }
      is(waitr_t) { // 按照手册，进行读取之前要等6个周期
        when(counter === 5.U) {
          state_t := r_data_t
          counter := 0.U
          data_reg := Cat(psram_cmd_io.io.odata(7, 0), psram_cmd_io.io.odata(15, 8), psram_cmd_io.io.odata(23, 16), psram_cmd_io.io.odata(31, 24))
        }.otherwise{
          counter := counter + 1.U
        }
      }
      is(r_data_t) {
        data_reg := Cat(data_reg(27, 0), 0.U(4.W))
        when (next_io_ce_n2) {
          // 说明已经进入了下一条 sck 命令的接收过程的第一个周期
          counter := 1.U
          cmd_reg := Mux(QPI, Cat(0.U(4.W), di), Cat(0.U(7.W), di(0)))
          addr_reg := 0.U 
          data_reg := 0.U
          data_counter := 0.U
          idata_reg := 0.U
          state_t := cmd_t
        }
      } 
      is(w_data_t) {
        ren := 1.B
        data_counter := data_counter + 4.U
        idata_reg := Cat(idata_reg(27, 0), di)
        when (next_io_ce_n2) {
          // 说明已经进入了下一条 sck 命令的接收过程的第一个周期
          counter := 1.U
          cmd_reg := Mux(QPI, Cat(0.U(4.W), di), Cat(0.U(7.W), di(0)))
          addr_reg := 0.U 
          data_reg := 0.U
          data_counter := 0.U
          idata_reg := 0.U
          state_t := cmd_t
          ren := 0.B
        }
      }
      is(err_t){
        printf("psram err at psram.scala\n");
        printf("cmd = %x\n", cmd_reg)
      }
    }
  }
}
class psram_cmd extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val rvalid = Input(Bool())
    val wvalid = Input(Bool())
    val wmask = Input(UInt(8.W))
    val bvalid = Output(Bool())
    val bready = Input(Bool())
    //val wen   = Input(Bool())
    // val cmd   = Input(UInt(8.W))
    val addr  = Input(UInt(32.W))
    val odata  = Output(UInt(32.W))
    val idata  = Input(UInt(32.W))
  })
  setInline("psram_cmd.v",
    """
    |import "DPI-C" function void psram_read(input int addr, output int odata);
    |
    |import "DPI-C" function void psram_write(input int addr, input int idata, input byte wmask);
    |
    |module psram_cmd(
    |input clk,
    |input rst,
    |input rvalid,
    |input wvalid,
    |input [7:0] wmask,
    |input [31:0] addr,
    |output reg [31:0] odata,
    |output reg bvalid,
    |input reg bready,
    |input [31:0] idata);
    |
    |always @(posedge clk or posedge rst) begin
    |  if (rvalid && !rst) begin 
    |    psram_read(addr, odata);
    |  end 
    |  if (wvalid && !rst && !bvalid) begin
    |    psram_write(addr, idata, wmask);
    |    bvalid <= 1'b1;
    |  end
    |  if (bvalid & bready) begin 
    |    bvalid <= 1'b0;
    |  end
    |end
    |endmodule
    """.stripMargin)
}
class APBPSRAM(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
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
    val qspi_bundle = IO(new QSPIIO)

    val mpsram = Module(new psram_top_apb)
    mpsram.io.clock := clock
    mpsram.io.reset := reset
    mpsram.io.in <> in
    qspi_bundle <> mpsram.io.qspi
  }
}
