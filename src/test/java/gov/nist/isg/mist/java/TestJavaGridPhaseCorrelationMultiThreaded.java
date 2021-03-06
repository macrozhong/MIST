// NIST-developed software is provided by NIST as a public service. You may use, copy and distribute copies of the software in any medium, provided that you keep intact this entire notice. You may improve, modify and create derivative works of the software or any portion of the software, and you may copy and distribute such modifications or works. Modified works should carry a notice stating that you changed the software and should note the date and nature of any such change. Please explicitly acknowledge the National Institute of Standards and Technology as the source of the software.

// NIST-developed software is expressly provided "AS IS." NIST MAKES NO WARRANTY OF ANY KIND, EXPRESS, IMPLIED, IN FACT OR ARISING BY OPERATION OF LAW, INCLUDING, WITHOUT LIMITATION, THE IMPLIED WARRANTY OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, NON-INFRINGEMENT AND DATA ACCURACY. NIST NEITHER REPRESENTS NOR WARRANTS THAT THE OPERATION OF THE SOFTWARE WILL BE UNINTERRUPTED OR ERROR-FREE, OR THAT ANY DEFECTS WILL BE CORRECTED. NIST DOES NOT WARRANT OR MAKE ANY REPRESENTATIONS REGARDING THE USE OF THE SOFTWARE OR THE RESULTS THEREOF, INCLUDING BUT NOT LIMITED TO THE CORRECTNESS, ACCURACY, RELIABILITY, OR USEFULNESS OF THE SOFTWARE.

// You are solely responsible for determining the appropriateness of using and distributing the software and you assume all risks associated with its use, including but not limited to the risks and costs of program errors, compliance with applicable laws, damage to or loss of data, programs or equipment, and the unavailability or interruption of operation. This software is not intended to be used in any situation where a failure could cause risk of injury or damage to property. The software developed by NIST employees is not subject to copyright protection within the United States.




// ================================================================
//
// Author: tjb3
// Date: Jul 2, 2014 11:51:33 AM EST
//
// Time-stamp: <Jul 2, 2014 11:51:33 AM tjb3>
//
//
// ================================================================
package gov.nist.isg.mist.java;


import java.io.File;
import java.io.FileNotFoundException;
import java.io.InvalidClassException;

import gov.nist.isg.mist.lib.imagetile.ImageTile;
import gov.nist.isg.mist.lib.imagetile.Stitching;
import gov.nist.isg.mist.lib.imagetile.java.JavaImageTile;
import gov.nist.isg.mist.lib.log.Log;
import gov.nist.isg.mist.lib.log.Log.LogType;
import gov.nist.isg.mist.lib.parallel.cpu.CPUStitchingThreadExecutor;
import gov.nist.isg.mist.lib.tilegrid.TileGrid;
import gov.nist.isg.mist.lib.tilegrid.loader.SequentialTileGridLoader;
import gov.nist.isg.mist.lib.tilegrid.loader.TileGridLoader;
import gov.nist.isg.mist.lib.tilegrid.loader.TileGridLoader.GridDirection;
import gov.nist.isg.mist.lib.tilegrid.loader.TileGridLoader.GridOrigin;
import gov.nist.isg.mist.timing.TimeUtil;

/**
 * Test case for stitching a grid of tiles with multithreading using Java.
 *
 * @author Tim Blattner
 * @version 1.0
 */
public class TestJavaGridPhaseCorrelationMultiThreaded {

  private static void runTestGridPhaseCorrelation() throws FileNotFoundException {
    Log.setLogLevel(LogType.HELPFUL);
    int startRow = 0;
    int startCol = 0;
    int extentWidth = 10;
    int extentHeight = 10;

    Log.msg(LogType.MANDATORY, "Running Test Grid Phase Correlation Multithreaded Java");

    File tileDir = new File("F:\\StitchingData\\70perc_input_images");

    Log.msg(LogType.HELPFUL, "Generating tile grid");
    TileGrid<ImageTile<float[][]>> grid = null;
    try {

      TileGridLoader loader =
          new SequentialTileGridLoader(42, 59, 1, 0,0,"F_{pppp}.tif", GridOrigin.UR,
              GridDirection.VERTICALCOMBING);

      grid =
          new TileGrid<ImageTile<float[][]>>(startRow, startCol, extentWidth, extentHeight, loader,
              tileDir, JavaImageTile.class);
    } catch (InvalidClassException e) {
      Log.msg(LogType.MANDATORY, e.getMessage());
    }

    Log.msg(LogType.HELPFUL, "Loading Java plan");

    if (grid == null)
      return;

    ImageTile<float[][]> tile = grid.getSubGridTile(0, 0);
    JavaImageTile.initJavaPlan(tile);

    CPUStitchingThreadExecutor<float[][]> executor =
        new CPUStitchingThreadExecutor<float[][]>(1, 8, tile, grid, null);

    Log.msg(LogType.HELPFUL, "Computing translations");
    TimeUtil.tick();
    executor.execute();

    Log.msg(LogType.MANDATORY, "Completed Stitching in " + TimeUtil.tock() + " ms");

    Stitching.printRelativeDisplacements(grid);

  }

  /**
   * Executes the test case
   *
   * @param args not used
   */
  public static void main(String args[]) {
    try {
      TestJavaGridPhaseCorrelationMultiThreaded.runTestGridPhaseCorrelation();
    } catch (FileNotFoundException e) {
      Log.msg(LogType.MANDATORY, "File not found: " + e.getMessage());
    }
  }
}
