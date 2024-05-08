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

class psramChisel extends RawModule {
  val io = IO(Flipped(new QSPIIO))
  val ren = WireInit(0.B)
  val dread = Wire(UInt(4.W))
  val di = TriStateInBuf(io.dio, dread, !ren) // change this if you need -> changed
  
  // 接收 data 时用的是 sck 的上升沿，接收 cmd 和 addr 时用的是下降沿
//   withClockAndReset((!io.sck).asClock, io.ce_n.asBool) {
//     val QPI = RegInit(0.B)
//     val cnt = RegInit(0.U(4.W))
//     val cmd_reg = RegInit(0.U(8.W))
//     // val wen_reg = RegInit(0.U(1.B))
//     val addr_reg = RegInit(0.U(24.W))
//     val data_reg = RegInit(0.U(32.W))
//     val idata_reg = RegInit(0.U(32.W))
//     val data_bswap = Cat(idata_reg(7, 0), idata_reg(15, 8), idata_reg(23, 16), idata_reg(31, 24))
//     val cmd_t :: addr_t :: waitr_t :: r_data_t :: w_data_t :: write_done_t :: err_t :: Nil = Enum(7)
//     val state_t = RegInit(cmd_t)
//     val counter = RegInit(0.U(8.W))
//     val psram_cmd_io = Module(new psram_cmd)
    
//     psram_cmd_io.io.clk := (io.sck).asClock
//     psram_cmd_io.io.rst := io.ce_n
//     psram_cmd_io.io.rvalid := (state_t === waitr_t) && (counter === 5.U) && (cmd_reg === "x6B".U) // 只会生效一个周期
//     psram_cmd_io.io.wvalid := (state_t === w_data_t) && (counter === 28.U) && (cmd_reg === "x38".U)
//     // psram_cmd_io.io.cmd := cmd_reg
//     psram_cmd_io.io.addr := addr_reg
//     // dwrite := idata_reg
//     // wen := wen_reg
//     dread := data_reg(31, 28)
//     psram_cmd_io.io.idata := data_bswap
//     switch(state_t) {
//       is(cmd_t) {
//         when(counter === Mux(QPI, 1.U, 7.U)) {
//           state_t := addr_t
//           counter := 0.U
//           addr_reg := Cat(addr_reg(19, 0), di)
//           when(cmd_reg === "x35".U) { // 进入 QPI 模式
//             QPI := 1.B
//           }.elsewhen(cmd_reg === "xf5".U){
//             QPI := 0.B
//           }
//         }.otherwise {
//           counter := counter + 1.U
//           cmd_reg := Mux(QPI, Cat(cmd_reg(3, 0), di), Cat(cmd_reg(6, 0), di(0)))
//         }
//       }
//       is(addr_t) {
        
//         when(counter === 20.U) {
//         // 地址也是按 4bit 传输，只有命令按 1 bit 传输
//           // state_t := Mux(cmd_reg === "x6B".U, waitr_t, Mux(cmd_reg === "x38".U, w_data_t, err_t))
//           when (cmd_reg === "x6B".U) {
//             state_t := waitr_t
//             counter := 0.U
//           }.elsewhen(cmd_reg === "x38".U) {
//             state_t := w_data_t
//             idata_reg := di
//             ren := 1.B
//           }.otherwise {
//             state_t := err_t
//           }
//           counter := 0.U
//           // addr_reg := Cat(0.U(2.W), addr_reg(23, 2))
//         }.otherwise {
//           counter := counter + 4.U
//           addr_reg := Cat(addr_reg(19, 0), di)
//         }
//       }
//       is(waitr_t) { // 按照手册，进行读取之前要等6个周期
//         when(counter === 5.U) {
//           state_t := r_data_t
//           counter := 0.U
//           data_reg := Cat(psram_cmd_io.io.odata(7, 0), psram_cmd_io.io.odata(15, 8), psram_cmd_io.io.odata(23, 16), psram_cmd_io.io.odata(31, 24))
//         }.otherwise{
//           counter := counter + 1.U
//         }
//       }
//       is(r_data_t) {
//         when (counter === 28.U) {
//           counter := 0.U
//           state_t := cmd_t
//           // io.ce_n := 1.B
//           addr_reg := 0.U
//           cmd_reg := 0.U
//         }.otherwise {
//           // 从仿真环境读出数据(只要一个周期)后，存到 datao 里然后每次4bit发回去
//           counter := counter + 4.U
//           // 模拟每次 4 bit 传输的过程
          
//           data_reg := Cat(data_reg(27, 0), 0.U(4.W)) 
//         }
//       } 
//       is(w_data_t) {
//         ren := 1.B
//         when (counter === 28.U) { // 当传输完成
//           counter := 0.U
//           state_t := cmd_t
//           ren := 0.B
//           cmd_reg := 0.U
//           addr_reg := 0.U
//         }.otherwise{
//           // 按照手册，传输是小端序，先传高位再传低位（第一次传输），SIO的低位是低位数据
//           idata_reg := Cat(idata_reg(27, 0), di)
//           counter := counter + 4.U
//         }
//       }
//       // is(write_done_t) {
//       //   state_t := cmd_t
//       //   counter := 0.U
//       //   // io.ce_n := 1.B
//       // }
//       is(err_t){
//         printf("psram err at psram.scala\n");
//         printf("cmd = %x\n", cmd_reg)
//       }
//     }
//   }

  // 接收 data cmd 和 addr 时用的是下降沿，
  // data 的长度不确定，传输结束的标志是 ce_n 被拉高，因此不能将传输 data 的判断放在 sck 时钟域里
  // 但 psram 颗粒用的是 RawModule，没有隐式的时钟和复位，因此应该用组合逻辑判断 data 传输的开始和完成
  val psram_cmd_io = Module(new psram_cmd)
  withClockAndReset((!io.sck).asClock, io.ce_n.asBool) {
    val counter = RegInit(0.U(8.W))
    val cmd_reg = RegInit(0.U(8.W))
    val addr_reg = RegInit(0.U(24.W))
    val idata_reg = RegInit(0.U(XLEN.W))  // 写入 psram 颗粒的数据存放在这个寄存器中
    val idata_bswap = Cat(idata_reg(7, 0), idata_reg(15, 8), idata_reg(23, 16), idata_reg(31, 24))
    val cmd_t :: addr_t :: waitr_t :: r_data_t :: w_data_t :: Nil = Enum(5)
    val state_t = RegInit(cmd_t)

  }
  psram_cmd_io.io.clk := (!io.sck).asClock  // 注意下降沿触发
  psram_cmd_io.io.rst := io.ce_n
  psram_cmd_io.io.rvalid := 
}
class psram_cmd extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val rvalid = Input(Bool())
    val wvalid = Input(Bool())
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
    |import "DPI-C" function void psram_write(input int addr, input int idata);
    |
    |module psram_cmd(
    |input clk,
    |input rst,
    |input rvalid,
    |input wvalid,
    |input [31:0] addr,
    |output reg [31:0] odata,
    |input [31:0] idata);
    |
    |always @(posedge clk or posedge rst) begin
    |  if(rvalid && !rst) begin 
    |    psram_read(addr, odata);
    |  end 
    |  if(wvalid && !rst) begin
    |    psram_write(addr, idata);
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
