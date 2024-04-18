package ysyx

import chisel3._
import chisel3.util._

class bitrev extends BlackBox {
  val io = IO(Flipped(new SPIIO(1)))
}

class bitrevChisel extends RawModule { // we do not need clock and reset
  val io = IO(Flipped(new SPIIO(1)))
  // io.miso := true.B // slave 空闲时需要将 miso 设为高

  withClockAndReset((io.sck).asClock, io.ss.asBool) {
    val cnt = RegInit(0.U(4.W))
    val empty_mosi = RegInit(1.B)
    val full_mosi = RegInit(0.B)
    val mosi_data_reg = RegInit(0.U(8.W))
    io.miso := 1.B
    
    // val miso_data_reg = RegInit(0.U(8.W))

    // val s_init :: s_rec :: s_send :: Nil = Enum(3)
    // val state = RegInit(s_init)
    // // switch (state) {
    // //   cnt := cnt + 1.U
    // //   is(s_init) {
    // //     mosi_data_reg := io.mosi
    // //     state := s_rec
    // //   }
    // //   is(s_rec) {
    // //     when(cnt === 0.U) {
    // //       state := s_send
    // //       mosi_data_reg := mosi_data_reg >> 1.U
    // //       io.miso := mosi_data_reg(0)
    // //     }
    // //   }
    // //   is(s_send) {
    // //     when(cnt === 0.U) {
    // //       state := s_rec

    // //     }
    // //   }
    // // }

    when(!io.ss) {
      io.miso := 1.B
      when(!full_mosi) {
        cnt := cnt + 1.U
        mosi_data_reg := io.mosi + (mosi_data_reg << 1.U)
        when (cnt === 7.U) {
          full_mosi := 1.B
          empty_mosi := 0.B
          cnt := 0.U
          io.miso := io.mosi
          // miso_data_reg := mosi_data_reg.reverse
        }
      }.otherwise {
        // io.miso := mosi_data_reg(0)
        // mosi_data_reg := mosi_data_reg >> 1.U
        // cnt := cnt + 1.U
        // when(cnt === 7.U) {
        //   empty_mosi := 1.B 
        //   full_mosi := 0.B
        //   cnt := 0.U
        // }
        when(cnt < 8.U) {
          cnt := cnt + 1.U
          io.miso := mosi_data_reg(cnt)
        }
      }
    }.otherwise {
      io.miso := 1.B
    }
  }
}

