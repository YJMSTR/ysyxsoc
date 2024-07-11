package millbuild

import _root_.mill.runner.MillBuildRootModule

object MiscInfo_build {
  implicit lazy val millBuildRootModuleInfo: _root_.mill.runner.MillBuildRootModule.Info = _root_.mill.runner.MillBuildRootModule.Info(
    Vector("/home/yjmstr/ysyx-workbench/ysyxSoC/out/mill-launcher/0.11.8.jar").map(_root_.os.Path(_)),
    _root_.os.Path("/home/yjmstr/ysyx-workbench/ysyxSoC"),
    _root_.os.Path("/home/yjmstr/ysyx-workbench/ysyxSoC"),
  )
  implicit lazy val millBaseModuleInfo: _root_.mill.main.RootModule.Info = _root_.mill.main.RootModule.Info(
    millBuildRootModuleInfo.projectRoot,
    _root_.mill.define.Discover[build]
  )
}
import MiscInfo_build.{millBuildRootModuleInfo, millBaseModuleInfo}
object build extends build
class build extends _root_.mill.main.RootModule {

//MILL_ORIGINAL_FILE_PATH=/home/yjmstr/ysyx-workbench/ysyxSoC/build.sc
//MILL_USER_CODE_START_MARKER

}