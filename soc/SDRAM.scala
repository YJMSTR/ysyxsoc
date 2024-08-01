package ysyx

import chisel3._
import chisel3.util._
import chisel3.experimental.Analog

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import scala.annotation.switch

class SDRAMIO extends Bundle {
  val clk = Output(Bool())
  val cke = Output(Bool())
  val cs  = Output(Bool())
  val ras = Output(Bool())
  val cas = Output(Bool())
  val we  = Output(Bool())
  val a   = Output(UInt(13.W))
  val ba  = Output(UInt(2.W))
  val dqm = Output(UInt(2.W))
  val dq  = Analog(16.W)
}

class sdram_top_axi extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val in = Flipped(new AXI4Bundle(AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 4)))
    val sdram = new SDRAMIO
  })
}

class sdram_top_apb extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val sdram = new SDRAMIO
  })
}

class sdram extends BlackBox {
  val io = IO(Flipped(new SDRAMIO))
}

class sdramChisel extends RawModule {
  val io = IO(Flipped(new SDRAMIO))
  val cmd = Cat(io.cs, io.ras, io.cas, io.we)
  val opcode = io.a(11, 0)
  val addr = io.a
  val bankid = io.ba 
  val ren = WireInit(0.B)
  val wen = WireInit(0.B)

  withClockAndReset((io.clk).asClock, ~io.cke) {
    val data_read = WireInit(0.U(16.W))
    data_read := 0.U
    val ren_reg = RegNext(ren)
    val data_write = TriStateInBuf(io.dq, data_read, ren)
    val idata_reg = RegInit(0.U(16.W))
    val active_bank = RegInit(0.U(2.W))
    // use reg of vec not vec of reg
    val active_row  = RegInit(VecInit(Seq.fill(4)(0.U(13.W))))
    val active_col  = RegInit(0.U(10.W))  // 一共 512 列，a[10] 用于标识是否 autocharge
    val currow = RegInit(0.U(13.W))
    val curbank = RegInit(0.U(2.W))
    val mode_register = RegInit(0.U(13.W))
    // cas latency
    // 2 -> 2
    // 3 -> 3
    // other: reserved
    val cas_latency = mode_register(6, 4)
    // bl
    // 0 -> 1
    // 1 -> 2
    // 2 -> 4
    // 3 -> 8
    // 4 ~ 7 : reserved
    val burst_length = 1.U << mode_register(2, 0)
    val mask = RegInit(0.U(8.W))


    //PRECHARGE和AUTO REFRESH命令与存储单元的电气特性相关, 在仿真环境中不必考虑, 
    //因此可以将其实现成NOP. 
    //
    //此外, Mode寄存器只需要实现CAS Latency和Burst Length, 其他字段可忽略.
    //需要考虑当前bank和接收指令的bank不同的情况，
    //根据手册，这种情况和当前bank等于接收指令的bank的状态定义不同
    //
    //command inhibit 命令阻止设备执行新命令，即使 cke = 0 也有效。
    //正在执行中的命令不会被影响
    //
    //NOP 命令 用于让 sdram 颗粒保持在 idle 或 wait 状态
    //
    //Load mode reg 命令只能在所有 bank 都处于 idle 时发出
    //
    //active 命令用于激活特定bank的特定行，以支持后续访问。
    //被激活的行会一直处于激活状态，
    //直到其所处的 bank 接收到 precharge 命令
    //在打开相同bank上的另一行之前，必须先对该bank执行precharge命令
    //
    //Read 命令用于在已激活的行上初始化一次突发读事务
    //此时 BA 选择 bank，a 选择列号，a[10]用于标识是否有 auto PRECHARGE
    //如果有 auto precharge，突发结束后会进行 precharge。但仿真环境中不用考虑
    //dq 里出现的读取数据 由两个周期之前的 dqm 决定
    //若 dqm 为高电平，dq 会在两个周期后变为 high-Z；
    //否则 dq 会在两个周期后输出有效数据
    //
    //Write 用于在已激活的行上初始化一次突发写事务
    //ba 选择 bank，a 选择列号，a[10]还是用于标识是否 auto precharge。
    //DQ 中的输入数据将在 DQM 信号与数据一致时被写入内存阵列，
    //如果 DQM 信号为高，那么对应的数据会被忽略，并且没有写入操作发生。
    val sdram_cmd_io = Module(new sdram_cmd)
    sdram_cmd_io.io.clk := (io.clk).asClock
    sdram_cmd_io.io.rvalid := ren
    sdram_cmd_io.io.wvalid := wen
    sdram_cmd_io.io.bank   := Cat(0.U(8.W), curbank)
    sdram_cmd_io.io.row    := Cat(0.U(3.W), currow)
    sdram_cmd_io.io.col    := Cat(0.U(6.W), active_col)
    data_read := sdram_cmd_io.io.odata
    sdram_cmd_io.io.idata  := 0.U
    sdram_cmd_io.io.mask   := mask
    
    val s_idle :: s_wait_read_cas :: s_row_active :: s_read :: s_write :: Nil = Enum(5)

    val s_state = RegInit(s_idle);
    val cas_counter = RegInit(0.U(3.W))
    val burst_counter = RegInit(0.U(3.W))

    switch(s_state) {
      is(s_idle) {
        when (io.cs) {
          // CMD inhibit(NOP)
        }.elsewhen (cmd === "b0111".U) {
          // NOP
        }.elsewhen (cmd === "b0011".U) {
          // active
          active_bank := bankid
          active_row(bankid) := addr
          curbank := bankid
          currow := addr
        }.elsewhen (cmd === "b0101".U) {
          // read
          active_bank := bankid
          active_col := addr(9, 0)
          curbank := bankid
    
          s_state := s_read
          cas_counter := 2.U
          burst_counter := 0.U
          mask := Cat(0.U(6.W), ~io.dqm)
          sdram_cmd_io.io.mask := Cat(0.U(6.W), ~io.dqm)
          currow := active_row(bankid)
        }.elsewhen (cmd === "b0100".U) {
          // write 收到命令的同时进行写入
          // sdram 控制器将 axi4 上的一次写事务拆分为两次连续的 sdram 写操作
          // 第一次写操作写入低半字，第二次写操作写入高半字
          active_bank := bankid
          currow := active_row(bankid)
          curbank := bankid
          sdram_cmd_io.io.row := active_row(bankid)
          sdram_cmd_io.io.bank := bankid
          active_col := addr(9, 0)
          sdram_cmd_io.io.col := Cat(0.U(6.W), addr(9, 0))
          mask := Cat(0.U(6.W), ~io.dqm)
          sdram_cmd_io.io.mask := Cat(0.U(6.W), ~io.dqm)
          // 下一个周期才会读出当前写入的 mask 的值，本周期需要直接连
          // active col 同理，但因为突发传输计算地址的需要，必须用 reg 保留地址
          burst_counter := 1.U
          wen := 1.B
          sdram_cmd_io.io.idata := data_write
          when (burst_length === 1.U) {
            // 当前周期就完成了写
            s_state := s_idle
            // 便于接收下一个指令
          //下面仅支持长度为2的突发写 
          }.otherwise {
            s_state := s_write
            burst_counter := 2.U
            active_col := addr(9,0)+1.U // 直接计算出下一个 beat 的地址
            // 如果当前 beat 是某一 row 的最后一个元素怎么办 特判
            when (addr(9, 0) + 1.U === 512.U) {
              active_col := 0.U
              when (currow + 1.U === 8192.U) {
                currow := 0.U
                curbank := bankid + 1.U
              }.otherwise {
                currow := addr(9, 0) + 1.U
              }
            }.otherwise{
              active_col := addr(9, 0) + 1.U
            }
          }
        }.elsewhen (cmd === "b0110".U) {
          // burst terminate
          // idle 状态下等于 NOP
        }.elsewhen (cmd === "b0010".U) {
          // precharge (NOP)
        }.elsewhen (cmd === "b0001".U) {
          // auto refresh(NOP)
        }.elsewhen (cmd === "b0000".U) {
          // load mode register
          // 该指令只能在 idle 执行
          // Mode寄存器只需要实现 CAS Latency 和 Burst Length, 
          // 其他字段可忽略.
          // M[2:0] burst length
          // M[3] type of burst           (NULL)
          // M[6:4] CAS length
          // M[7] and M[8] operating mode (NULL)
          // M[9] write burst mode        (NULL)
          // others :  zero
          // a[11:0] 是要写入 mode register 的 opcode
          mode_register := opcode
        }.otherwise {
          printf("unsupport sdram cmd %x\n", cmd);
        }
      }
      is(s_wait_read_cas) {
        when (cas_counter === cas_latency) {
          s_state := s_read
          burst_counter := 1.U
        }.otherwise {
          cas_counter := cas_counter + 1.U
        }
      }
      is(s_read) {
        ren := 1.B
        when (burst_counter === burst_length) {
          s_state := s_idle
          cas_counter := 0.U 
          burst_counter := 0.U
        }.otherwise {
          burst_counter := burst_counter + 1.U
          // 先读的低字节
          when (active_col + 1.U === 512.U) {
            active_col := 0.U
            when (currow + 1.U === 8192.U) {
              currow := 0.U
              curbank := curbank + 1.U
            }.otherwise {
              currow := currow + 1.U
            }
          }.otherwise{
            active_col := active_col + 1.U
          }
        }
      }
      // 最多一次写 32 位数据，分成两次 write
      is(s_write) {
        wen := 1.B
        sdram_cmd_io.io.idata := data_write
        mask := Cat(0.U(6.W), ~io.dqm)
        sdram_cmd_io.io.mask := Cat(0.U(6.W), ~io.dqm)
        when (burst_counter === burst_length) {
          s_state := s_idle
          burst_counter := 0.U
        }.otherwise{
          // 下面这段其实根本不会进来
  
        }
      }
    }
  }
}

class sdram_cmd extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clk     = Input(Clock())
    val rvalid  = Input(Bool())
    val wvalid  = Input(Bool())
    val bank    = Input(UInt(8.W))
    val row     = Input(UInt(16.W))
    val col     = Input(UInt(16.W))
    val odata   = Output(UInt(16.W))
    val idata   = Input(UInt(16.W))
    val mask    = Input(UInt(8.W))
  })
  setInline("sdram_cmd.v",
    """
    |import "DPI-C" function void sdram_read(input byte bank, input shortint row, input shortint col, output shortint odata, input byte mask);
    |import "DPI-C" function void sdram_write(input byte bank, input shortint row, input shortint col, input shortint idata, input byte mask);
    |module sdram_cmd(
    |input                 clk,
    |input                 rvalid,
    |input                 wvalid,
    |input [7:0]           bank,
    |input [15:0]          row,
    |input [15:0]          col,
    |output reg [15:0]     odata,
    |input reg [15:0]      idata,
    |input reg [7:0]       mask);
    |always @(posedge clk) begin
    |  if (rvalid) begin
    |    sdram_read(bank, row, col, odata, mask);
    |    //$display("soc sdram_cmd_io read bank=%x row=%x col=%x odata=%x mask=%x\n", bank, row, col, odata, mask);
    |  end
    |  if (wvalid) begin
    |    sdram_write(bank, row, col, idata, mask);
    |    //$display("soc sdram_cmd_io write bank=%x row=%x col=%x idata=%x mask=%x\n", bank, row, col, idata, mask);
    |  end
    |end
    |endmodule
    """.stripMargin)

}

class AXI4SDRAM(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val beatBytes = 8
  val node = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
    Seq(AXI4SlaveParameters(
        address       = address,
        executable    = true,
        supportsWrite = TransferSizes(1, beatBytes),
        supportsRead  = TransferSizes(1, beatBytes),
        interleavedId = Some(0))
    ),
    beatBytes  = beatBytes)))

  private val outer = this

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val sdram_bundle = IO(new SDRAMIO)

    val msdram = Module(new sdram_top_axi)
    msdram.io.clock := clock
    msdram.io.reset := reset.asBool
    msdram.io.in <> in
    sdram_bundle <> msdram.io.sdram
  }
}

class APBSDRAM(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
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
    val sdram_bundle = IO(new SDRAMIO)

    val msdram = Module(new sdram_top_apb)
    msdram.io.clock := clock
    msdram.io.reset := reset.asBool
    msdram.io.in <> in
    sdram_bundle <> msdram.io.sdram
  }
}
