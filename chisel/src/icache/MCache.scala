/*
 Method Cache for Patmos
 Author: Philipp Degasperi (philipp.degasperi@gmail.com)
 */

/*
 Replacement Strategy

 LRU Replacement with fixed block size (MAYBE USE THE LRU DESIGN with shift registers to save list with pointers
 --> cost: addr_tag , size_tag, list(prev/next), search logic: all depending on method count in cache, LRU/MRU reg
 
 LRU Replacement with variable size

 VARIABLE BLOCK SIZE its getting more complicated we have to track the position of each
 ...but we run into problems when replacing there is needed an adress translation

 FIFO with variable block size
 --> cost: addr_tag, size_tag, pos_tag, search logic, next_index reg, next_replace reg, free_space reg
 
 FIFO with fixed block size could be implemented for comparison, this is straight forward just use
 a next_tag instead of the lru_tag at replacement and no lru table is needed than...
 --> cost: addr_tag, size_tag, search logic: all depending on method count in cache, NEXT reg

*/

//merge package mcache and icache in future
package patmos

import Chisel._
import Node._
import ExtMemROM._
import MConstants._
import Constants._
import MCacheMem._

import scala.collection.mutable.HashMap
import scala.util.Random
import scala.math

object MConstants {
  //on chip 4KB icache
  val MCACHE_SIZE = 1024 //4096 / 4 //* 8 //4KB = 2^12*2^3 = 32*1024 = 32768Bit
  val EXTMEM_SIZE = 2 * MCACHE_SIZE // =32*2048
  val METHOD_COUNT = 4
  val METHOD_BLOCK_SIZE = MCACHE_SIZE / METHOD_COUNT
  val METHOD_SIZETAG_WIDTH = log2Up(MCACHE_SIZE)
  val METHOD_COUNT_WIDTH = log2Up(METHOD_COUNT)
  val WORD_COUNT = 4
  val LRU_REPL = 1
  val FIFO_REPL = 2
  val FIXED_SIZE = 1
  val VARIABLE_SIZE = 2

  //DEBUG INFO
  println("MCACHE_SIZE=" + MCACHE_SIZE)
  println("EXTMEM_SIZE=" + EXTMEM_SIZE)
  println("METHOD_BLOCK_SIZE=" + METHOD_BLOCK_SIZE)
  println("METHOD_COUNT=" + METHOD_COUNT)
  println("METHOD_SIZETAG_WIDTH=" + METHOD_SIZETAG_WIDTH)
  println("METHOD_COUNT_WIDTH=" + METHOD_COUNT_WIDTH)
  println("WORD_COUNT=" + WORD_COUNT)
}

class MCacheIn extends Bundle() {
  val address = Bits(width = 32)
  val doCallRet = Bits(width = 1)
  val callRetBase = Bits(width = 32)
  val request = Bits(width = 1) //should be used in future maybe when data cache stalls?
}
class MCacheOut extends Bundle() {
  val instr_a = Bits(width = 32) //lower 32 bits
  val instr_b = Bits(width = 32) //higher 32 bits
  val hit = Bits(width = 1) //hit/stall signal
}
class MCacheIO extends Bundle() {
  val mcachemem_in = new MCacheMemIn().asOutput
  val mcachemem_out = new MCacheMemOut().asInput
  val mcache_in = new MCacheIn().asInput
  val mcache_out = new MCacheOut().asOutput
  val extmem_in = new ExtMemIn().asOutput
  val extmem_out = new ExtMemOut().asInput
}

//TODO will be moved to a OPC interface!
/* cache memory of icache and mcache should be integrated in one mem.scala class and one package: cache 
   ...at least external Memory since it is a simulation of a extmem and the same for icache and mcache
*/
class ExtMemIn extends Bundle() {
  val address = Bits(width = 32)
  val msize = Bits(width = METHOD_SIZETAG_WIDTH) //size or block count to fetch
  val fetch = Bits(width = 1)
}
class ExtMemOut extends Bundle() {
  val data = Bits(width = 32)
  val ready = Bits(width = 1)
}
class ExtMemIO extends Bundle() {
  val extmem_in = new ExtMemIn().asInput
  val extmem_out = new ExtMemOut().asOutput
}


class MCacheMemIn extends Bundle() {
  val w_enable = Bits(width = 1)
  val w_data = Bits(width = 32)
  val address = Bits(width = 32) //should be 32 because we need whole address for tag?!
  val w_tag = Bits(width = 1)
  val doCallRet = Bits(width = 1)
  val callRetBase = Bits(width = 32)
}
class MCacheMemOut extends Bundle() {
  val instr_a = Bits(width = INSTR_WIDTH)
  val instr_b = Bits(width = INSTR_WIDTH)
  val hit = Bits(width = 1)
}
class MCacheMemIO extends Bundle() {
  val mcachemem_in = new MCacheMemIn().asInput
  val mcachemem_out = new MCacheMemOut().asOutput
}

object MCacheMem {

  //move some functions here...

  def get_address(pos : Bits,  offset : Bits) : Bits = {
    ((pos + (offset / UFix(2))) % Bits(MCACHE_SIZE)) //modulo if you write over the cache size (variable blocks)
  }

  //TODO: should integrate the check for variable blocks too...
  def check_block_size(size : Bits) : Bits = {
    ((size - Bits(1)) / Bits(METHOD_BLOCK_SIZE))
  }
 
}


/*
 memory logic of the method cache
*/
class MCacheMem( 
  method_count : Int = METHOD_COUNT,
  replacement : Int = FIFO_REPL,
  block_arrangement : Int = FIXED_SIZE
) extends Component {
  val io = new MCacheMemIO()
  val ram_mcache_even = Mem(MCACHE_SIZE / 2, seqRead = true) {Bits(width = INSTR_WIDTH)}
  val ram_mcache_odd = Mem(MCACHE_SIZE / 2, seqRead = true) {Bits(width = INSTR_WIDTH)}

  val mcache_addr_tag = Mem(method_count) {Bits(width = 32)}
  val mcache_size_tag = Mem(method_count) {Bits(width = METHOD_SIZETAG_WIDTH)}
  val mcache_pos_tag = Mem(method_count) {Bits(width = METHOD_SIZETAG_WIDTH)} //neeeded only when variable size is used


  //linked list for LRU replacement
  val mcache_list_prev = Mem(method_count) {Bits(width = log2Up(method_count))}
  val mcache_list_next = Mem(method_count) {Bits(width = log2Up(method_count))}


  //save base block for FIXED block size arrangement
  val mcache_base_block = Mem(method_count) {Bits(width = METHOD_SIZETAG_WIDTH)}
  val mcache_base_addr = Mem(method_count) {Bits(width = 32)}

  //regs
  val dout_a = Reg() {Bits(width = INSTR_WIDTH)}
  val dout_b = Reg() {Bits(width = INSTR_WIDTH)}
  val dout_hit = Reg() {Bits(width = 1)}
  //keep track of lru and mru of the list

//TODO why this doesnt work???? is synthesized away...
//if (replacement == LRU_REPL) {
  val lru_tag = Reg(resetVal = Bits(0, width = log2Up(method_count)))
  val mru_tag = Reg(resetVal = Bits(method_count - 1, width = log2Up(method_count)))
//}

  val next_index_tag = Reg(resetVal = Bits(0, width = log2Up(method_count)))
  val next_replace_tag = Reg(resetVal = Bits(0, width = log2Up(method_count)))
  val next_replace_pos = Reg(resetVal = Bits(0, width = METHOD_SIZETAG_WIDTH))
  val free_space = Reg(resetVal = Fix(MCACHE_SIZE))


  //for splitting up methods if needed
  val split_mcounter = Reg(resetVal = Bits(0, width = log2Up(method_count)))
  val split_maddress = Reg(resetVal = Bits(0, width = 32))
  val split_msize = Reg(resetVal = Bits(0, width = METHOD_SIZETAG_WIDTH))
  val split_base_tag = Reg(resetVal = Bits(0, width = METHOD_SIZETAG_WIDTH))
  val split_base_addr = Reg(resetVal = Bits(0, width = 32))

  //signals
  val data_even = Bits(width = INSTR_WIDTH)
  val data_odd = Bits(width = INSTR_WIDTH)

//TODO:
//should not need this, list of differences betweeen 3 operating modes!
  if (replacement == FIFO_REPL) {
    if (block_arrangement == FIXED_SIZE) {
      next_index_tag := Bits(0)
      next_replace_pos := Bits(0)
      free_space := Fix(0)
    }
    else if (block_arrangement == VARIABLE_SIZE) {
      split_mcounter := Bits(0)
      split_maddress := Bits(0)
      split_msize := Bits(0)
    }
    split_base_tag := Bits(0)
    split_base_addr := Bits(0)
    lru_tag := Bits(0)
    mru_tag := Bits(0)
  }
  else if (replacement == LRU_REPL) {
    next_index_tag := Bits(0)
    next_replace_tag := Bits(0)
    next_replace_pos := Bits(0)
    free_space := Fix(0)
  }

  //init signals
  data_even := Bits(0)
  data_odd := Bits(0)

  //signal of the current tagfield with position and hit result
  class TagField extends Bundle {
    val pos = Bits(width = METHOD_SIZETAG_WIDTH)
    val hit = Bits(width = 1)
    val tag = Bits(width = 32)
  }
  val tag_field = new TagField()
  tag_field.pos := Bits(0)
  tag_field.hit := Bits(0)
  tag_field.tag := Bits(0)

  //saves the current tagfield position since fetch can only occur on call/return
  val pos = Reg(resetVal = Bits(0, width = METHOD_SIZETAG_WIDTH))
  val hit = Reg(resetVal = Bits(0, width = 1))
  val tag = Reg(resetVal = Bits(0, width = 32))

  //search the given addr in the tag field
  def search_tag_addr(addr : Bits, tagfield: TagField) = {
    for (i <- 0 until method_count) {
      when ((addr >= mcache_addr_tag(Bits(i))) && (addr < (mcache_addr_tag(Bits(i)) + mcache_size_tag(Bits(i))))) {
        if (block_arrangement == FIXED_SIZE) {
          tagfield.pos := Bits(i) * Bits(METHOD_BLOCK_SIZE / 2) // divided by two because even odd memory!
        }
        else if (block_arrangement == VARIABLE_SIZE) {
          tagfield.pos := mcache_pos_tag(Bits(i))
        }
        tagfield.hit := Bits(1)
        tagfield.tag := mcache_addr_tag(Bits(i))
      }
    }
  }

  //CHECK BLOCK SiZE only for FIXED SiZE when read/write at next block not do it with variable size!
  //search needed or not
  when(io.mcachemem_in.doCallRet === Bits(1) || (check_block_size(io.mcachemem_in.address - tag) != Bits(0))) {
    when(io.mcachemem_in.callRetBase < io.mcachemem_in.address && io.mcachemem_in.doCallRet === Bits(1)) {
      search_tag_addr(io.mcachemem_in.callRetBase, tag_field)
    }
    //baseically at start but think we could drop this inserting a call/ret at start in mcache
    .otherwise {
      search_tag_addr(io.mcachemem_in.address, tag_field)
    }

    if (replacement == LRU_REPL) {
      when (tag_field.hit === Bits(1) && io.mcachemem_in.w_enable === Bits(0)) {
        update_tag(tag_field.pos / Bits(METHOD_BLOCK_SIZE / 2))
      }

    }

    pos := tag_field.pos
    hit := tag_field.hit
    tag := tag_field.tag
  }
  .otherwise {
    tag_field.pos := pos
    tag_field.hit := hit
    tag_field.tag := tag
  }

  val addr_offset = (io.mcachemem_in.address - tag_field.tag) //offset between incoming address and base address

//TODO only for LRU
//TODO how we can init Memories??? programming init in C?! following only for simulation in chisel!
  def init_tag_list() = {
    when (list_init === Bits(0)) {
      for (i <- 0 until 4) {
        mcache_list_next(Bits(i)) := Bits(i) - Bits(1)
        mcache_list_prev(Bits(i)) := Bits(i) + Bits(1)
      }
    }
  }
  val list_init = Reg(resetVal = Bits(0, width = 1))
  when (list_init === Bits(0)) {
    if (replacement == LRU_REPL) {
      init_tag_list()
    }
  }
  list_init := Bits(1)

  //update tag field
  def update_tag(tag : Bits) = {
    if (replacement == FIFO_REPL) {
      tag := (tag + Bits(1)) % Bits(method_count)
    }
    else if (replacement == LRU_REPL) {
      when (tag === lru_tag) {
        lru_tag := mcache_list_prev(tag)
        mru_tag := tag
        mcache_list_next(tag) := mru_tag
        mcache_list_prev(mru_tag) := tag
        mcache_list_prev(tag) := tag //no previous any more because mru
      }
      .elsewhen (tag != mru_tag) {
        mcache_list_next(mcache_list_prev(tag)) := mcache_list_next(tag)
        mcache_list_prev(mcache_list_next(tag)) := mcache_list_prev(tag)
        mcache_list_next(tag) := mru_tag
        mcache_list_prev(mru_tag) := tag
        mru_tag := tag
      }
    }
  }

  //prepare replacement: index the size and the address tag and check the size fits into one block/free size otherwise split up
  when (io.mcachemem_in.w_tag === Bits(1)) {

    if (replacement == FIFO_REPL && block_arrangement == VARIABLE_SIZE) {
      //enough free space to fill up
      when (free_space >= io.mcachemem_in.w_data) {
        free_space := free_space - io.mcachemem_in.w_data + mcache_size_tag(next_index_tag)
      }
      .otherwise {
        free_space := free_space + mcache_size_tag(next_replace_tag) - io.mcachemem_in.w_data
      }

      next_replace_pos := (next_replace_pos + io.mcachemem_in.w_data) % Bits(MCACHE_SIZE)
      mcache_pos_tag(next_index_tag) := next_replace_pos
      mcache_size_tag(next_index_tag) := io.mcachemem_in.w_data
      mcache_addr_tag(next_index_tag) := io.mcachemem_in.address

      pos := next_replace_pos
      hit := Bits(1)
      tag := io.mcachemem_in.address

      update_tag(next_index_tag)
    }

    if (block_arrangement == FIXED_SIZE) {
      //everything fine method fits into one block
      when (check_block_size(io.mcachemem_in.w_data) === Bits(0)) {
        if (replacement == FIFO_REPL) {
          mcache_size_tag(next_replace_tag) := io.mcachemem_in.w_data
          mcache_addr_tag(next_replace_tag) := io.mcachemem_in.address

          pos := next_replace_tag * Bits(METHOD_BLOCK_SIZE / 2)
          hit := Bits(1)
          tag := io.mcachemem_in.address

          //when (mcache_addr_tag(mcache_base_block(next_replace_tag)) === mcache_base_addr(next_replace_tag)) {
          //  mcache_size_tag(mcache_base_block(next_replace_tag)) := Bits(0)
          //}

          update_tag(next_replace_tag)

        }
        else if (replacement == LRU_REPL) {
          mcache_size_tag(lru_tag) := io.mcachemem_in.w_data
          mcache_addr_tag(lru_tag) := io.mcachemem_in.address

          pos := lru_tag * Bits(METHOD_BLOCK_SIZE / 2)
          hit := Bits(1)
          tag := io.mcachemem_in.address

          when (mcache_addr_tag(mcache_base_block(lru_tag)) === mcache_base_addr(lru_tag)) {
            mcache_size_tag(mcache_base_block(lru_tag)) := Bits(0)
          }
          update_tag(lru_tag)
        }
      }
      //split up in more blocks and set counter
      .otherwise {
        split_mcounter := check_block_size(io.mcachemem_in.w_data)
        split_maddress := io.mcachemem_in.address + Bits(METHOD_BLOCK_SIZE)
        split_msize := io.mcachemem_in.w_data - Bits(METHOD_BLOCK_SIZE)
        if (replacement == FIFO_REPL) {
         
          mcache_size_tag(next_replace_tag) := Bits(METHOD_BLOCK_SIZE)
          mcache_addr_tag(next_replace_tag) := io.mcachemem_in.address
          update_tag(next_replace_tag)

          //split_base_tag := next_replace_tag
          //split_base_addr := io.mcachemem_in.address
          //when (mcache_addr_tag(mcache_base_block(next_replace_tag)) === mcache_base_addr(next_replace_tag)) {
          //  mcache_size_tag(mcache_base_block(next_replace_tag)) := Bits(0)
          //}

          pos := next_replace_tag * Bits(METHOD_BLOCK_SIZE / 2)
          hit := Bits(1)
          tag := io.mcachemem_in.address

        }
        else if (replacement == LRU_REPL) {
          split_base_tag := lru_tag
          split_base_addr := io.mcachemem_in.address
          mcache_size_tag(lru_tag) := Bits(METHOD_BLOCK_SIZE)
          mcache_addr_tag(lru_tag) := io.mcachemem_in.address

          pos := lru_tag * Bits(METHOD_BLOCK_SIZE / 2)
          hit := Bits(1)
          tag := io.mcachemem_in.address

          when (mcache_addr_tag(mcache_base_block(lru_tag)) === mcache_base_addr(lru_tag)) {
            mcache_size_tag(mcache_base_block(lru_tag)) := Bits(0)
          }

          update_tag(lru_tag)
        }
      }
    }

  }

  //more space is needed!
  if (replacement == FIFO_REPL && block_arrangement == VARIABLE_SIZE) {
    //the following handles a possible split up if more space is needed
    when (free_space < Fix(0)) {
      free_space := free_space + mcache_size_tag(next_replace_tag)
      mcache_size_tag(next_replace_tag) := Bits(0)
      update_tag(next_replace_tag)
    }
  }

  
  if (block_arrangement == FIXED_SIZE) {
    when (split_mcounter != Bits(0)) {
      //one more block is needed
      when (check_block_size(split_msize) === Bits(0)) {
        if (replacement == FIFO_REPL) {
          mcache_size_tag(next_replace_tag) := split_msize
          mcache_addr_tag(next_replace_tag) := split_maddress

          //mcache_base_block(next_replace_tag) := split_base_tag
          //mcache_base_addr(next_replace_tag) := split_base_addr
          //when (mcache_addr_tag(mcache_base_block(next_replace_tag)) === mcache_base_addr(next_replace_tag)) {
          //  mcache_size_tag(mcache_base_block(next_replace_tag)) := Bits(0)
          //}

          update_tag(next_replace_tag)
        }
        else if (replacement == LRU_REPL) {
          mcache_size_tag(lru_tag) := split_msize
          mcache_addr_tag(lru_tag) := split_maddress
          mcache_base_block(lru_tag) := split_base_tag
          mcache_base_addr(lru_tag) := split_base_addr

          when (mcache_addr_tag(mcache_base_block(lru_tag)) === mcache_base_addr(lru_tag)) {
            mcache_size_tag(mcache_base_block(lru_tag)) := Bits(0)
          }

          update_tag(lru_tag)
        }
      }
      //split up in even more blocks
      .otherwise {
        split_maddress := split_maddress + Bits(METHOD_BLOCK_SIZE)
        split_msize := split_msize - Bits(METHOD_BLOCK_SIZE)
        if (replacement == FIFO_REPL) {
          mcache_size_tag(next_replace_tag) := Bits(METHOD_BLOCK_SIZE)
          mcache_addr_tag(next_replace_tag) := split_maddress


          //mcache_base_block(next_replace_tag) := split_base_tag
          //mcache_base_addr(next_replace_tag) := split_base_addr
          //when (mcache_addr_tag(mcache_base_block(next_replace_tag)) === mcache_base_addr(next_replace_tag)) {
          //  mcache_size_tag(mcache_base_block(next_replace_tag)) := Bits(0)
          //}

          update_tag(next_replace_tag)
        }
        else if (replacement == LRU_REPL) {
          mcache_size_tag(lru_tag) := Bits(METHOD_BLOCK_SIZE)
          mcache_addr_tag(lru_tag) := split_maddress
          mcache_base_block(lru_tag) := split_base_tag
          mcache_base_addr(lru_tag) := split_base_addr

          when (mcache_addr_tag(mcache_base_block(lru_tag)) === mcache_base_addr(lru_tag)) {
            mcache_size_tag(mcache_base_block(lru_tag)) := Bits(0)
          }

          update_tag(lru_tag)
        }
      }
      split_mcounter := split_mcounter - Bits(1)
    }

  }

  //write
  when (io.mcachemem_in.w_enable) {

//TODO: should always be a hit... not used...
    when (tag_field.hit === Bits(1)) {
      when (addr_offset(0) === Bits(1)) {
        ram_mcache_odd(get_address(tag_field.pos, addr_offset)) := io.mcachemem_in.w_data
      }
      .otherwise {
        ram_mcache_even(get_address(tag_field.pos, addr_offset)) := io.mcachemem_in.w_data
      }
    }
  }
  //read
  .otherwise {
    when (tag_field.hit === Bits(1)) {
      data_even := ram_mcache_even(Mux(addr_offset(0), (get_address(tag_field.pos, addr_offset) + Bits(1)), get_address(tag_field.pos, addr_offset)))
      data_odd := ram_mcache_odd(get_address(tag_field.pos, addr_offset))
    }
    dout_hit := tag_field.hit
    dout_a := Mux(addr_offset(0), data_odd, data_even) //instr_a must be set here because instr_a and b depend on offset
    dout_b := Mux(addr_offset(0), data_even, data_odd) //instr_b
  }

  io.mcachemem_out.instr_a := dout_a
  io.mcachemem_out.instr_b := dout_b 
  io.mcachemem_out.hit := dout_hit
}

/*
 Object of external memory implemented in ROM
 */
object ExtMemROM {

  //external memory instance
  val rom_extmem = Vec(EXTMEM_SIZE) {Bits(width = 32)} //how is the bus width?
  
  /**
   * Read a binary file into the ROM vector, from Utility.scala
     Author: Martin Schoeberl
   */
  def initROM_bin(fileName: String): Vec[Bits] = { 
    println("Reading " + fileName)
    // an encodig to read a binary file? Strange new world.
    val source = scala.io.Source.fromFile(fileName)(scala.io.Codec.ISO8859)
    val byteArray = source.map(_.toByte).toArray
    source.close()
    for (i <- 0 until byteArray.length / 4) {
      var word = 0
      for (j <- 0 until 4) {
        word <<= 8
        word += byteArray(i * 4 + j).toInt & 0xff
      }
      printf("%08x\n", word)
      // mmh, width is needed to keep bit 31
      rom_extmem(i) = Bits(word, width=32)
    }
    // generate some dummy data to fill the table and make Bit 31 test happy
    for (x <- byteArray.length / 4 until EXTMEM_SIZE)
      rom_extmem(x) = Bits("h8000000000000000")
    rom_extmem
  }
}

/*
 External memory implemented as ROM onchip in chisel...
 TODO: ADD a delay for simulation of a real external memory access penalty
 */
class ExtMemROM(filename: String) extends Component {
  val io = new ExtMemIO()
  val rom_init = Reg(resetVal = Bits(0, width = 1))
  val dout = Reg(resetVal = Bits(0, width = 32))
  val dout_ready = Reg(resetVal = Bits(0, width = 1))
  val burst_counter = Reg(resetVal = UFix(0, width = 32))
  val read_address = Reg(resetVal = UFix(0))


  //reading something into rom for debugging
  rom_init := Bits(1)
  when (rom_init === Bits(0)) {
    initROM_bin(filename)
  }

  when (io.extmem_in.fetch) {
    dout := rom_extmem(io.extmem_in.address)
    dout_ready := Bits(1)
    read_address := io.extmem_in.address + UFix(1)
    burst_counter := (io.extmem_in.msize - UFix(1)) % UFix(MCACHE_SIZE - 1) //if msize = 0... todo: not even start transfer here
  }
  .elsewhen (burst_counter != Bits(0)) {
    dout := rom_extmem(read_address)
    dout_ready := Bits(1)
    burst_counter := burst_counter - UFix(1)
    read_address := read_address + UFix(1)
  }
  .otherwise {
    dout := Bits(0)
    dout_ready := Bits(0)
  }

  io.extmem_out.data := dout
  io.extmem_out.ready := dout_ready

}

class MCache(fileName : String) extends Component {
  val io = new MCacheIO()

  //fsm variables
  val init_state :: idle_state :: size_state :: transfer_state :: restart_state :: Nil = Enum(5){ UFix() }
  val mcache_state = Reg(resetVal = init_state)

  //signals
  val extmem_fetch = Bits(width = 1)
  val extmem_fetch_address = Bits(width = 32)
  val extmem_msize = Bits(width = METHOD_SIZETAG_WIDTH)
  val mcachemem_address = Bits(width = 32) //???
  val mcache_w_enable = Bits(width = 1)
  val mcachemem_w_data = Bits(width = DATA_WIDTH)
  val mcachemem_wtag = Bits(width = 1)
  val mcachemem_doCallRet = Bits(width = 1)

  val mcache_instr_a = Bits(width = DATA_WIDTH)
  val mcache_instr_b = Bits(width = DATA_WIDTH)
  val mcachemem_hit = Bits(width = 1)

  //regs
  val transfer_size = Reg(resetVal = Bits(0, width = METHOD_SIZETAG_WIDTH))
  val fword_counter = Reg(resetVal = Bits(0, width = 32))
  val mcache_address = Reg(resetVal = Bits(0, width = 32)) //save address in case no hit occours
  //val mcache_address_next = Reg(resetVal = Bits(0, width = 32)) //save next address in case of fetch to restart fast

  //init signals
  extmem_fetch := Bits(0)
  extmem_fetch_address := Bits(0)
  extmem_msize := Bits(0)
  mcachemem_wtag := Bits(0)
  mcachemem_w_data := Bits(0)
  mcache_w_enable := Bits(0)
  mcachemem_address := io.mcache_in.address
  mcachemem_doCallRet := io.mcache_in.doCallRet

  mcachemem_hit := io.mcachemem_out.hit
  mcache_instr_a := io.mcachemem_out.instr_a
  mcache_instr_b := io.mcachemem_out.instr_b

  //init state needs to fetch at program counter - 1 the first size of method block
  when (mcache_state === init_state) {
    when(io.mcache_in.request) {
      mcache_address := io.mcache_in.address - Bits(1)
      mcache_state := idle_state
    }
  }
  //check if instruction is available
  when (mcache_state === idle_state) {
    when(io.mcachemem_out.hit === Bits(1)) {
      mcache_address := io.mcache_in.callRetBase //io.mcache_in.address
    }
    //no hit... fetch from external memory
    .otherwise {
      //mcache_address_next := io.mcache_in.address //save pc + 4 for restart
      mcache_state := size_state
      extmem_fetch := Bits(1)
      extmem_fetch_address := mcache_address - Bits(1) // -1 because size is at method head -1
      extmem_msize := Bits(1) //here we could fetch already one first block instead single size tag
    }
  }

  //fetch size of the required method from external memory
  when (mcache_state === size_state) {
    when (io.extmem_out.ready === Bits(1)) {
      fword_counter := io.extmem_out.data / Bits(WORD_COUNT) //size is given in bytes not words
      extmem_fetch_address := mcache_address //fetch from extmem with latched address
      extmem_msize := io.extmem_out.data / Bits(WORD_COUNT) //size of words zu fetch
      transfer_size := io.extmem_out.data / Bits(WORD_COUNT) //save transfer size because extmem is accessed in burst mode
      mcachemem_wtag := Bits(1)  //init transfer in mcachemem
      mcachemem_w_data := io.extmem_out.data / Bits(WORD_COUNT) //write size to mcachemem for LRU tagfield
      mcachemem_address := mcache_address //write base address to mcachemem for LRU tagfield
      extmem_fetch := Bits(1)
      mcache_state := transfer_state
    }
  }

  //transfer/fetch method to the cache
  when (mcache_state === transfer_state) {
    when (fword_counter > Bits(0)) {
      when (io.extmem_out.ready === Bits(1)) {
        fword_counter := fword_counter - Bits(1)
        mcachemem_w_data := io.extmem_out.data //write fetched data to method cache memory
        mcache_w_enable := Bits(1)
        mcachemem_address := mcache_address + (transfer_size - fword_counter) //adress is base address + offset
      }
    }
    .otherwise {
      mcachemem_address := mcache_address
      mcache_state := idle_state
    }
  }

//TODO: NOT NEEDED any more...
  //restart using the latched address_next address
  when (mcache_state === restart_state) {
    mcachemem_address := io.mcache_in.address //mcache_address_next
    mcache_address := io.mcache_in.address //mcache_address_next
    mcache_state := idle_state
  }

  //set output signals
  io.mcachemem_in.address := mcachemem_address
  io.mcachemem_in.w_enable := mcache_w_enable
  io.mcachemem_in.w_data := mcachemem_w_data
  io.mcachemem_in.w_tag := mcachemem_wtag
  io.mcachemem_in.doCallRet := mcachemem_doCallRet //io.mcache_in.doCallRet
  io.mcachemem_in.callRetBase := io.mcache_in.callRetBase

  io.mcache_out.instr_a := mcache_instr_a //io.mcachemem_out.instr_a
  io.mcache_out.instr_b := mcache_instr_b //io.mcachemem_out.instr_b
  io.mcache_out.hit := mcachemem_hit //io.mcachemem_out.hit

  io.extmem_in.address := extmem_fetch_address
  io.extmem_in.fetch := extmem_fetch
  io.extmem_in.msize := extmem_msize

}
