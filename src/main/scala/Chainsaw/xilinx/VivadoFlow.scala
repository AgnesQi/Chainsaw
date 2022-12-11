package Chainsaw.xilinx

import Chainsaw._
import org.apache.commons.io.FileUtils
import spinal.core._
import spinal.lib._

import java.io.File
import scala.collection.mutable
import scala.io.Source
import scala.language.{existentials, postfixOps}
import scala.util.{Failure, Success, Try}


/** used to generate sources for a Vivado flow and invoke Vivado to run it
 */
class VivadoFlow[T <: Component](
                                  design: => T,
                                  taskType: EdaFlowType,
                                  xilinxDevice: XilinxDevice,
                                  topModuleName: String,
                                  workspacePath: File,
                                  xdcFile: Option[File] = None,
                                  netlistFile: Option[File] = None
                                ) {

  val isWindows = System.getProperty("os.name").toLowerCase().contains("win")

  /** core function of Vivado flow, used to run a synth/impl task
   *
   * @return VivadoReport as an object
   */
  def doFlow(): VivadoReport = {
    atSimTime = false // synth, rather than sim

    // create workspace directory
    workspacePath.mkdirs()
    val tclFile = new File(workspacePath, "doit.tcl")
    val simpleXdcFile = new File(workspacePath, "doit.xdc")
    val logFile = new File(workspacePath, "doit.log")

    // generate sources from dut
    // TODO: use netlistDir instead of netlistFile
    val rtlResources = netlistFile match {
      case Some(src) =>
        val des = new File(workspacePath, src.getName)
        FileUtils.copyFile(src, des)
        Seq(des.getAbsolutePath)
      case None =>
        val config = SpinalConfig(
          defaultConfigForClockDomains = xilinxCDConfig,
          targetDirectory = workspacePath.getAbsolutePath + "/",
          oneFilePerComponent = true)
        // TODO: subtract the additional FFs from synth/impl result?
        //        config.addTransformationPhase(new phases.FfIo)
        val spinalReport = config.generateVerilog(design.setDefinitionName(topModuleName))
        //        spinalReport.rtlSourcesPaths
        val lstFile = Source.fromFile(new File(workspacePath, s"$topModuleName.lst"))
        val ret = lstFile.getLines().map { line => new File(line) }.map(_.getAbsolutePath).toSeq
        //        lstFile.close()
        ret
    }

    // generate xdc & tcl file
    val simpleLine = {
      val targetPeriod = xilinxDevice.fMax.toTime
      s"""create_clock -period ${(targetPeriod * 1e9) toBigDecimal} [get_ports clk]"""
    }
    FileUtils.write(simpleXdcFile, simpleLine)

    // priority: specific xdc > device xdc > simple xdc
    val xdcCandidates = Seq(xdcFile, xilinxDevice.xdcFile, Some(simpleXdcFile))
    val xdcFileInUse = xdcCandidates.flatten.head

    FileUtils.write(tclFile, getTcl(rtlResources, xdcFileInUse))

    // run vivado
    DoCmd.doCmd(
      s"${vivadoPath.getAbsolutePath} -stack 2000 -nojournal -log ${logFile.getAbsolutePath} -mode batch -source ${tclFile.getAbsolutePath}",
      workspacePath.getAbsolutePath
    )

    /** --------
     * report
     * -------- */
    val report = new VivadoReport(logFile, xilinxDevice.family) // parse log file to get report
    logger.info(s"\n----vivado flow report----\n" + report.toString)
    atSimTime = true
    report
  }

  /** generate tcl script content for Vivado Flow
   *
   * @param dutRtlSources paths of rtl sources generated by dut
   * @return context of tcl script
   */
  def getTcl(dutRtlSources: Seq[String], xdcFile: File): String = {
    var script = ""

    def getReadCommand(sourcePath: File) = {
      if (sourcePath.getPath.endsWith(".sv")) s"read_verilog -sv $sourcePath \n"
      else if (sourcePath.getPath.endsWith(".v")) s"read_verilog $sourcePath \n"
      else if (sourcePath.getPath.endsWith(".vhdl") || sourcePath.getPath.endsWith(".vhd"))
        s"read_vhdl $sourcePath \n"
      else if (sourcePath.getPath.endsWith(".bin")) "\n"
      else
        throw new IllegalArgumentException(
          s"invalid RTL source path $sourcePath"
        )
    }

    // read design sources
    // sources from dut
    //    netlistFile match {
    //      case Some(netlist) => script += getReadCommand(netlist)
    //      case None =>
    //        val lstFile = Source.fromFile(new File(workspacePath, s"$topModuleName.lst"))
    //        lstFile.getLines().foreach { line =>
    //          val sourceFile = new File(line)
    //          script += getReadCommand(sourceFile)
    //        }
    //        lstFile.close()
    //    }

    dutRtlSources.foreach(path => script += getReadCommand(new File(path)))

    // read constraint sources
    script += s"read_xdc ${xdcFile.getAbsolutePath}\n"

    // do flow
    def addSynth(): Unit = {
      script += s"synth_design -part ${xilinxDevice.part} -top $topModuleName -mode out_of_context\n"
      script += s"write_checkpoint -force ${topModuleName}_after_synth.dcp\n"
      script += s"report_timing\n"
    }

    def addImpl(): Unit = {
      script += "opt_design\n"
      script += "place_design -directive Explore\n"
      script += "report_timing\n"
      script += s"write_checkpoint -force ${topModuleName}_after_place.dcp\n"
      script += "phys_opt_design\n"
      script += "report_timing\n"
      script += s"write_checkpoint -force ${topModuleName}_after_place_phys_opt.dcp\n"
      script += "route_design\n"
      script += s"write_checkpoint -force ${topModuleName}_after_route.dcp\n"
      script += "report_timing\n"
      script += "phys_opt_design\n"
      script += "report_timing\n"
      script += s"write_checkpoint -force ${topModuleName}_after_route_phys_opt.dcp\n"
    }

    taskType match {
      case SYNTH =>
        addSynth()
      case IMPL =>
        addSynth()
        addImpl()
      // TODO: implement BitGen
      case BITGEN => ???
    }
    // util & timing can't be reported after synth/impl
    //    script += s"report_utilization -hierarchical -hierarchical_depth 2\n"
    script += s"report_utilization\n"
    script
  }
}

object VivadoFlow {
  def apply[T <: Module](design: => T, taskType: EdaFlowType, xilinxDevice: XilinxDevice, topModuleName: String, workspacePath: File, xdcFile: Option[File]): VivadoFlow[T] =
    new VivadoFlow(design, taskType, xilinxDevice, topModuleName, workspacePath, xdcFile)
}

object DefaultVivadoFlow {
  def general[T <: Module](design: => T, name: String, flowType: EdaFlowType, netlistFile: Option[File]) = {
    val flow = new VivadoFlow(design, flowType, vu9p, name, new File(synthWorkspace, name), netlistFile = netlistFile)
    flow.doFlow()
  }
}

object VivadoSynth {
  def apply[T <: Module](design: => T, name: String) = DefaultVivadoFlow.general(design, name, SYNTH, None)

  def apply[T <: Module](netlistFile: File, topModuleName: String) = DefaultVivadoFlow.general(null, topModuleName, SYNTH, Some(netlistFile))
}

object VivadoImpl {
  def apply[T <: Module](design: => T, name: String) = DefaultVivadoFlow.general(design, name, IMPL, None)

  def apply[T <: Module](netlistFile: File, topModuleName: String) = DefaultVivadoFlow.general(null, topModuleName, IMPL, Some(netlistFile))
}