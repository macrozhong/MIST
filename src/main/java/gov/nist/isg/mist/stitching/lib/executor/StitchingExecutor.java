// ================================================================
//
// Disclaimer: IMPORTANT: This software was developed at the National
// Institute of Standards and Technology by employees of the Federal
// Government in the course of their official duties. Pursuant to
// title 17 Section 105 of the United States Code this software is not
// subject to copyright protection and is in the public domain. This
// is an experimental system. NIST assumes no responsibility
// whatsoever for its use by other parties, and makes no guarantees,
// expressed or implied, about its quality, reliability, or any other
// characteristic. We would appreciate acknowledgement if the software
// is used. This software can be redistributed and/or modified freely
// provided that any derivative works bear some notice that they are
// derived from it, and any modified versions bear some notice that
// they have been modified.
//
// ================================================================

// ================================================================
//
// Author: tjb3
// Date: Apr 18, 2014 1:34:00 PM EST
//
// Time-stamp: <Apr 18, 2014 1:34:00 PM tjb3>
//
//
// ================================================================

package gov.nist.isg.mist.stitching.lib.executor;

import gov.nist.isg.mist.stitching.gui.params.StitchingAppParams;
import gov.nist.isg.mist.stitching.gui.params.objects.RangeParam;
import gov.nist.isg.mist.stitching.lib.exceptions.StitchingException;
import gov.nist.isg.mist.stitching.lib.optimization.OptimizationRepeatability;
import gov.nist.isg.mist.stitching.lib.optimization.OptimizationUtils;
import gov.nist.isg.mist.stitching.lib32.executor.CudaStitchingExecutor32;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.MessageDialog;
import ij.macro.Interpreter;
import ij.plugin.frame.Recorder;
import jcuda.CudaException;
import jcuda.driver.CUdeviceptr;
import gov.nist.isg.mist.stitching.MIST;
import gov.nist.isg.mist.stitching.MIST.ExecutionType;
import gov.nist.isg.mist.stitching.StitchingGUIFrame;
import gov.nist.isg.mist.stitching.gui.StitchingGuiUtils;
import gov.nist.isg.mist.stitching.gui.StitchingStatistics;
import gov.nist.isg.mist.stitching.gui.StitchingStatistics.RunTimers;
import gov.nist.isg.mist.stitching.gui.StitchingStatusFrame;
import gov.nist.isg.mist.stitching.lib.export.LargeImageExporter;
import gov.nist.isg.mist.stitching.lib.export.blend.AverageBlend;
import gov.nist.isg.mist.stitching.lib.export.blend.Blender;
import gov.nist.isg.mist.stitching.lib.export.blend.LinearBlend;
import gov.nist.isg.mist.stitching.lib.export.blend.OverlayBlend;
import gov.nist.isg.mist.stitching.lib.imagetile.ImageTile;
import gov.nist.isg.mist.stitching.lib.imagetile.Stitching;
import gov.nist.isg.mist.stitching.lib.imagetile.fftw.FftwImageTile;
import gov.nist.isg.mist.stitching.lib.log.Log;
import gov.nist.isg.mist.stitching.lib.log.Log.LogType;
import gov.nist.isg.mist.stitching.lib.optimization.GlobalOptimization;
import gov.nist.isg.mist.stitching.lib.tilegrid.TileGrid;
import gov.nist.isg.mist.stitching.lib.tilegrid.TileGridUtils;
import jcuda.driver.JCudaDriver;
import jcuda.jcufft.JCufft;

import org.bridj.Pointer;

import javax.swing.*;

import java.awt.*;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InvalidClassException;
import java.util.List;

/**
 * StitchingExecutor is the thread that executes stitching.
 *
 * @author Tim Blattner
 * @version 1.0
 */
public class StitchingExecutor implements Runnable {

  /**
   * Type of stitching execution to be done
   *
   * @author Tim Blattner
   * @version 1.0
   */
  public static enum StitchingType {
    /**
     * Automatically determines stitching type
     */
    AUTO("Auto"),

    /**
     * Selects JAVA version
     */
    JAVA("JAVA"),

    /**
     * Selects FFTW version
     */
    FFTW("FFTW"),

    /**
     * Selects CUDA version
     */
    CUDA("CUDA");

    private StitchingType(final String text) {
      this.text = text;
    }

    private final String text;

    @Override
    public String toString() {
      return this.text;
    }
  }

  /**
   * The stitching statistics for stitching
   */
  private StitchingStatistics stitchingStatistics;

  private static final String outOfMemoryMessage =
      "Error: Insufficient memory to execute stitching.\n" + "Please increase JVM maximum memory.";

  private static final long OneGB = 1073741824L;


  private StitchingGUIFrame stitchingGUI;
  private StitchingStatusFrame stitchingExecutionFrame;

  private JProgressBar progressBar;
  private JLabel progressLabel;
  private StitchingAppParams params;
  private ExecutionType executionType;

  private Thread optimizationThread;
  private GlobalOptimization<?> globalOptimization;
  private LargeImageExporter<?> imageExporter;

  private volatile boolean isCancelled;

  private StitchingExecutorInterface<?> executor;


  /**
   * Initializes the stitching execution with only params
   *
   * @param params the stitching app parameters
   */
  public StitchingExecutor(StitchingAppParams params) {
    this(null, null, params);
  }

  /**
   * Initializes the stitching execution
   *
   * @param stitchingGUI the stitching application GUI
   * @param type         the type of execution to be done
   * @param params       the stitching app parameters
   */
  public StitchingExecutor(StitchingGUIFrame stitchingGUI, ExecutionType type, StitchingAppParams params) {
    this.params = params;
    this.stitchingGUI = stitchingGUI;
    this.executionType = type;
    this.optimizationThread = null;
    this.progressBar = null;
    this.progressLabel = null;
    this.isCancelled = false;
    this.imageExporter = null;
  }

  @Override
  public void run() {

    try {
      switch (this.executionType) {
        case RunStitching:
          runStitchingWithGUI();
          break;
        case RunStitchingMacro:
          runStitchingWithMacro();
          break;
        case RunStitchingFromMeta:
          runStitchingFromMeta();
          break;
        case PreviewNoOverlap:
          try {
            previewNoOverlap();
          } catch (FileNotFoundException e) {
            Log.msg(LogType.MANDATORY, "File not found: " + e.getMessage() + " Cancelling preview.");
          }
          break;
        case LoadParams:
        case RunStitchingFromMetaMacro:
        case SaveParams:
        default:
          break;

      }
    } catch (StitchingException e) {
      Log.msg(LogType.MANDATORY, e.getMessage());
    }

    if (this.stitchingExecutionFrame != null)
      this.stitchingExecutionFrame.dispose();
    MIST.disableStitching();
  }

  /**
   * Cancels execution
   */
  public void cancelExecution() {
    this.isCancelled = true;
    if (this.executor != null) {
      this.executor.cancelExecution();
    }

    this.cancelOptimization();
    this.cancelExport();
  }


  /**
   * Cancels the optimization thread
   */
  public void cancelOptimization() {
    if (this.optimizationThread != null && this.optimizationThread.isAlive()) {
      this.globalOptimization.cancelOptimization();
      this.optimizationThread.interrupt();
    }
  }

  public void cancelExport() {
    if (this.imageExporter != null) {
      this.imageExporter.cancel();
    }
  }


  private void runStitchingWithGUI() throws StitchingException {
    Log.msg(LogType.MANDATORY, "Checking args for stitching:");

    if (this.stitchingGUI.checkAndParseGUI(this.params)) {
      Log.msg(LogType.MANDATORY, "Arg check passed");
      if (Recorder.record || Recorder.recordInMacros) {
        Log.msg(LogType.MANDATORY, "Recording macros");
        this.params.recordMacro();
      }

      runStitching(false, true, false);
    } else {
      Log.msg(LogType.MANDATORY, "Stitching parameter check failed. "
          + "Invalid values are highlighted in red");
    }
  }

  private void runStitchingWithMacro() throws StitchingException {
    this.params.loadMacro();

    if (this.params.checkParams()) {
      if (this.params.getInputParams().isAssembleFromMetadata())
        runStitchingFromMeta();
      else
        runStitching(false, true, false);
    } else {
      Log.msg(LogType.MANDATORY, "Stitching parameter check failed. "
          + "Check the console for information. (increase logging " + "level for more details)");
    }
  }

  private void runStitchingFromMeta() throws StitchingException {
    Log.msg(LogType.MANDATORY, "Checking args for stitching:");

    if (this.stitchingGUI == null || this.stitchingGUI.checkAndParseGUI(this.params)) {
      Log.msg(LogType.MANDATORY, "Arg check passed");
      if (Recorder.record || Recorder.recordInMacros) {
        Log.msg(LogType.MANDATORY, "Recording macros");
        this.params.recordMacro();
      }

      assembleFromMeta();
    } else {
      Log.msg(LogType.MANDATORY, "Stitching parameter check failed. "
          + "Invalid values are highlighted in red");
    }


  }

  private void assembleFromMeta() throws StitchingException {

    this.params.getOutputParams().setOutputMeta(false);

    runStitching(true, true, false);
  }

  /**
   * Executes stitching
   *
   * @param assembleFromMeta            whether to assemble from meta data or not
   * @param displayGui                  whether to display any gui or not
   * @param stopExecutionIfFileNotFound sets whether to throws a stitching exception if a file not
   *                                    found exception is found
   */
  @SuppressWarnings("unchecked")
  public <T> void runStitching(boolean assembleFromMeta, boolean displayGui, boolean stopExecutionIfFileNotFound) throws StitchingException {
    this.stitchingStatistics = new StitchingStatistics(this.params);
    if (GraphicsEnvironment.isHeadless() || Interpreter.isBatchMode())
      displayGui = false;

    StitchingExecutorInterface<T> stitchingExecutorInf = null;

    if (assembleFromMeta) {
      stitchingExecutorInf = (StitchingExecutorInterface<T>) new AssembleFromMetaExecutor<Pointer<Double>>();
      this.params.getOutputParams().setOutputMeta(false); // if assembling from meta, don't also output meta
    } else {
      switch (this.params.getAdvancedParams().getProgramType()) {
        case AUTO:
          // initialize and check fftw first
          if (params.getAdvancedParams().isUseDoublePrecision()) {
            String libFN = params.getAdvancedParams().getFftwLibraryFileName();
            if (libFN.startsWith("libfftw3f")) {
              params.getAdvancedParams().setFftwLibraryFileName("libfftw3" + libFN.substring(9));
            }
            libFN = params.getAdvancedParams().getFftwLibraryName();
            if (libFN.startsWith("libfftw3f")) {
              params.getAdvancedParams().setFftwLibraryName("libfftw3" + libFN.substring(9));
            }

            stitchingExecutorInf = (StitchingExecutorInterface<T>) new FftwStitchingExecutor<Pointer<Double>>(this);
          } else {
            String libFN = params.getAdvancedParams().getFftwLibraryFileName();
            if (!libFN.startsWith("libfftw3f") && libFN.startsWith("libfftw3")) {
              params.getAdvancedParams().setFftwLibraryFileName("libfftw3f" + libFN.substring(8));
            }
            libFN = params.getAdvancedParams().getFftwLibraryName();
            if (!libFN.startsWith("libfftw3f") && libFN.startsWith("libfftw3")) {
              params.getAdvancedParams().setFftwLibraryName("libfftw3f" + libFN.substring(8));
            }

            stitchingExecutorInf = (StitchingExecutorInterface<T>) new FftwStitchingExecutor<Pointer<Float>>(this);
          }

          // If the libs are not available for FFTW, then check Java
          if (!stitchingExecutorInf.checkForLibs(this.params, displayGui)) {
            stitchingExecutorInf = (StitchingExecutorInterface<T>) new JavaStitchingExecutor<float[][]>();
          }

          break;
        case CUDA:
          if(this.params.getAdvancedParams().isUseDoublePrecision()) {
            stitchingExecutorInf = (StitchingExecutorInterface<T>) new CudaStitchingExecutor<CUdeviceptr>(this);
          }else{
            stitchingExecutorInf = (StitchingExecutorInterface<T>) new CudaStitchingExecutor32<CUdeviceptr>(this);
          }

          JCufft.setExceptionsEnabled(params.getAdvancedParams().isEnableCudaExceptions());
          JCudaDriver.setExceptionsEnabled(params.getAdvancedParams().isEnableCudaExceptions());

          break;
        case FFTW:
          if (params.getAdvancedParams().isUseDoublePrecision()) {
            String libFN = params.getAdvancedParams().getFftwLibraryFileName();
            if (libFN.startsWith("libfftw3f")) {
              params.getAdvancedParams().setFftwLibraryFileName("libfftw3" + libFN.substring(9));
            }
            libFN = params.getAdvancedParams().getFftwLibraryName();
            if (libFN.startsWith("libfftw3f")) {
              params.getAdvancedParams().setFftwLibraryName("libfftw3" + libFN.substring(9));
            }

            stitchingExecutorInf = (StitchingExecutorInterface<T>) new FftwStitchingExecutor<Pointer<Double>>(this);
          } else {
            String libFN = params.getAdvancedParams().getFftwLibraryFileName();
            if (!libFN.startsWith("libfftw3f") && libFN.startsWith("libfftw3")) {
              params.getAdvancedParams().setFftwLibraryFileName("libfftw3f" + libFN.substring(8));
            }
            libFN = params.getAdvancedParams().getFftwLibraryName();
            if (!libFN.startsWith("libfftw3f") && libFN.startsWith("libfftw3")) {
              params.getAdvancedParams().setFftwLibraryName("libfftw3f" + libFN.substring(8));
            }

            stitchingExecutorInf = (StitchingExecutorInterface<T>) new FftwStitchingExecutor<Pointer<Float>>(this);
          }
          break;
        case JAVA:
          stitchingExecutorInf = (StitchingExecutorInterface<T>) new JavaStitchingExecutor<float[][]>();
          break;
        default:
          break;

      }
    }

    this.executor = stitchingExecutorInf;

    if (stitchingExecutorInf == null || !stitchingExecutorInf.checkForLibs(this.params, displayGui))
      return;


    // Check for overwriting files, and confirm with user
    ExistingFilesChecker fileChecker = new ExistingFilesChecker(this.params);
    if (!fileChecker.checkExistingFiles(displayGui))
      return;


    if (!params.getInputParams().isSuppressSubGridWarning()) {
      // open dialog telling the user if they are stitching with a subgrid
      int dH = params.getInputParams().getGridHeight() - params.getInputParams().getExtentHeight();
      int dW = params.getInputParams().getGridWidth() - params.getInputParams().getExtentWidth();
      if (dH != 0 || dW != 0) {
        Log.msg(LogType.MANDATORY, "MIST is stitching a Sub Grid.");
        if (displayGui && !GraphicsEnvironment.isHeadless() && !Interpreter.isBatchMode()) {
          String[] options = {"Ok",
              "Cancel"};
          int n = JOptionPane.showOptionDialog(stitchingGUI,
              "MIST is stitching a Sub Grid.",
              "Warning: SubGrid",
              JOptionPane.YES_NO_OPTION,
              JOptionPane.WARNING_MESSAGE,
              null,
              options,
              options[0]);
          if (n == 1) {
            this.cancelExecution();
            return;
          }
        }

      }
    }


    if (displayGui && !GraphicsEnvironment.isHeadless() && !Interpreter.isBatchMode()) {
      if (this.stitchingGUI != null)
        this.stitchingGUI.performExit();


      this.stitchingExecutionFrame = new StitchingStatusFrame(this);

      this.progressBar = this.stitchingExecutionFrame.getProgressBar();
      this.progressLabel = this.stitchingExecutionFrame.getProgressLabel();


      this.stitchingExecutionFrame.display();
    }


    Log.msg(LogType.MANDATORY, "STITCHING BEGINS!");
    this.stitchingStatistics.setExecutionType(this.params.getAdvancedParams().getProgramType());
    this.stitchingStatistics.startEndToEndTimer();

    if (this.params.getAdvancedParams().getNumFFTPeaks() != 0)
      Stitching.NUM_PEAKS = this.params.getAdvancedParams().getNumFFTPeaks();

    List<RangeParam> timeSlices = this.params.getInputParams().getTimeSlices();
    int group = 0;
    for (RangeParam timeSliceParam : timeSlices) {
      group++;

      int minTimeSlice = timeSliceParam.getMin();
      int maxTimeSlice = timeSliceParam.getMax();

      for (int timeSlice = minTimeSlice; timeSlice <= maxTimeSlice; timeSlice++) {
        if (this.isCancelled) {
          this.cancelExecution();
          return;
        }

        this.stitchingStatistics.setCurrentTimeSlice(timeSlice);
        this.stitchingStatistics.addTimeSlice(timeSlice);


        if (this.params.getInputParams().isTimeSlicesEnabled()) {
          StitchingGuiUtils.updateProgressLabel(this.progressLabel, timeSlice,
              maxTimeSlice, group, timeSlices.size());
        }


        boolean runSequential = false;
        TileGrid<ImageTile<T>> grid;
        boolean optimizationSuccessful;
        try {

          grid = stitchingExecutorInf.initGrid(this.params, timeSlice);

          if (grid == null)
            return;


          ImageTile.disableFreePixelData();
          // Check if there is enough memory to process this grid
          if (!executor.checkMemory(grid, params.getAdvancedParams().getNumCPUThreads())) {
            ImageTile.enableFreePixelData();
            Log.msg(LogType.MANDATORY,
                "Insufficient memory to hold all image tiles in memory, turning on the freeing of pixel data");

            if (!executor.checkMemory(grid, params.getAdvancedParams().getNumCPUThreads())) {
              Log.msg(LogType.MANDATORY,
                  "Insufficient memory to perform stitching with " + params
                      .getAdvancedParams().getNumCPUThreads()
                      + " threads, attempting backoff for timeslice: "
                      + timeSlice);
              Log.msg(LogType.MANDATORY,
                  "SUGGESTION: Try lowering the number of compute threads which lowers the memory requirements");

              // perform thread count backoff to find what maximum number of threads can be supported
              for (int n = params.getAdvancedParams().getNumCPUThreads(); n >= 1; n--) {
                params.getAdvancedParams().setNumCPUThreads(n);
                if (executor.checkMemory(grid, params.getAdvancedParams().getNumCPUThreads()))
                  break;
              }
              Log.msg(LogType.MANDATORY,
                  "Attempting to perform stitching with " + params
                      .getAdvancedParams().getNumCPUThreads() + " threads.");

              // check if the 1 thread method has sufficient memory, if not run sequential stitching
              if (params.getAdvancedParams().getNumCPUThreads() == 1) {
                if (!executor.checkMemory(grid, params.getAdvancedParams().getNumCPUThreads())) {
                  // only run sequential stitching if not assembling from metadata
                  if (!params.getInputParams().isAssembleFromMetadata()) {
                    Log.msg(LogType.MANDATORY,
                        "Attempting to use sequential stitching, this version is expected to take awhile (see FAQ for suggestions)");

                    runSequential = true;
                    stitchingExecutorInf =
                        (StitchingExecutorInterface<T>) new SequentialJavaStitchingExecutor<float[][]>();
                    grid = stitchingExecutorInf.initGrid(this.params, timeSlice);
                    // update the executor reference because it has been changed to sequential
                    this.executor = stitchingExecutorInf;

                    this.stitchingStatistics.setIsRunSequential(true);
                  }
                }
              }
            }
          }

          this.stitchingStatistics.startTimer(RunTimers.TotalStitchingTime);
          initProgressBar();
          this.stitchingStatistics.startTimer(RunTimers.RelativeDisplacementTime);

          stitchingExecutorInf.launchStitching(grid, this.params, this.progressBar, timeSlice);

          this.stitchingStatistics.stopTimer(RunTimers.RelativeDisplacementTime);

          optimizationSuccessful = optimizeAndComposeGrid(grid, this.progressBar, assembleFromMeta, runSequential, params.getAdvancedParams().isUseDoublePrecision());

          this.stitchingStatistics.stopTimer(RunTimers.TotalStitchingTime);


        } catch (OutOfMemoryError e) {
          showError(outOfMemoryMessage);
          Log.msg(LogType.MANDATORY,
              "SUGGESTION: Try lowering the number of compute threads which lowers the memory requirements");
          throw new StitchingException("Out of memory thrown: " + outOfMemoryMessage, e);
        } catch (CudaException e) {
          showError("CUDA exception thrown: " + e.getMessage());
          throw new StitchingException("CUDA exception thrown: " + e.getMessage(), e);
        } catch (FileNotFoundException e) {
          Log.msg(LogType.MANDATORY,
              "Error unable to find file: " + e.getMessage() + ". Skipping timeslice: "
                  + timeSlice);

          if (stopExecutionIfFileNotFound)
            throw new StitchingException("Error unable to find file: " + e.getMessage() + ". Failed at timeslice: " + timeSlice, e);
          else
            continue;
        } catch (IllegalArgumentException e) {
          throw new StitchingException("Illegal argument: " + e.getMessage(), e);

        } catch (Throwable e) {

          Log.msg(LogType.MANDATORY, "Error occurred in stitching worker: " + e.toString());
          for (StackTraceElement st : e.getStackTrace())
            Log.msg(LogType.MANDATORY, st.toString());
          throw new StitchingException("Error occurred in stitching worker", e);
        }


        if (this.params.getInputParams().isTimeSlicesEnabled()) {
          Log.msg(
              LogType.MANDATORY,
              "Completed Stitching in "
                  + this.stitchingStatistics.getDuration(RunTimers.TotalStitchingTime) + "ms" + " time slice: "
                  + timeSlice + " of " + maxTimeSlice);
        } else {
          Log.msg(
              LogType.MANDATORY,
              "Completed Stitching in "
                  + this.stitchingStatistics.getDuration(RunTimers.TotalStitchingTime) + "ms");
        }

        // Always create the output directory
        File outputDir = new File(this.params.getOutputParams().getOutputPath());
        outputDir.mkdirs();

        if (optimizationSuccessful) {
          try {
            if (this.params.getOutputParams().isOutputMeta()) {
              this.outputMeta(grid, this.progressBar, timeSlice);
            }

            if (checkOutputGridMemory(grid)) {
              outputGrid(grid, this.progressBar, timeSlice);
            } else {
              if(this.params.getOutputParams().isOutputFullImage())
                Log.msg(LogType.MANDATORY, "Not enough memory to create output stitched image.");
            }
          } catch (FileNotFoundException e) {
            Log.msg(LogType.MANDATORY,
                "Unable find file: " + e.getMessage() + ". Cancelling writing full image.");
          }
        }

        releaseTiles(grid);
      }
    }

    this.stitchingStatistics.stopEndToEndTimer();
    this.stitchingStatistics.writeStatistics(this.params.getOutputParams().getStatsFile());
    this.stitchingStatistics.writeLog(this.params.getOutputParams().getLogFile());


    if (displayGui) {

      int displayWarningDialog = 0;
      String accumulatedWarningString = "";
      for (RangeParam timeSliceParam : timeSlices) {
        int minTimeSlice = timeSliceParam.getMin();
        int maxTimeSlice = timeSliceParam.getMax();

        for (int timeSlice = minTimeSlice; timeSlice <= maxTimeSlice; timeSlice++) {
          String str = this.stitchingStatistics.runErrorChecks(timeSlice);
          if (!str.contains(StitchingStatistics.ErrorReportStatus.PASSED.toString())) {
            displayWarningDialog++;
            accumulatedWarningString = accumulatedWarningString + str + "\n";
          }
        }
      }

      if (displayWarningDialog > 0) {
        File statsFile = this.params.getOutputParams().getStatsFile();
        String warnStr = "Stitching experiment(s) generated warnings:\n \n";
        if (displayWarningDialog == 1) {
          warnStr = warnStr + accumulatedWarningString + "\n \n";
        }
        warnStr = warnStr + "For more details check the log or the statistics file:\n" + statsFile
            .getAbsolutePath();
        new MessageDialog(IJ.getInstance(), "Stitching Warning", warnStr);
      }
    }


    this.executor.cleanup();
  }


  /**
   * Initializes the progress bar based on the total translations of extent-width and
   * extent-height.
   */
  public void initProgressBar() {
    final int totalTranslations =
        (this.params.getInputParams().getExtentHeight() * (this.params.getInputParams().getExtentWidth() - 1))
            + ((this.params.getInputParams().getExtentHeight() - 1) * this.params.getInputParams().getExtentWidth());

    StitchingGuiUtils.updateProgressBar(this.progressBar, false, null, "Stitching...", 0,
        totalTranslations, 0, false);
  }


  /*
   * Returns true if the optimization was successful, otherwise false.
   */
  private <T> boolean optimizeAndComposeGrid(final TileGrid<ImageTile<T>> grid, final JProgressBar progressBar, boolean assembleFromMeta, boolean runSequential, boolean useDoublePrecision)
      throws Throwable {

    if (this.isCancelled)
      return false;

    if (assembleFromMeta)
      return true;

    StitchingGuiUtils.updateProgressBar(progressBar, true, "Computing optimization");

    this.stitchingStatistics.startTimer(RunTimers.GlobalOptimizationTime);

    Stitching.USE_HILLCLIMBING = this.params.getAdvancedParams().isUseHillClimbing();


    OptimizationUtils.backupTranslations(grid);


    OptimizationRepeatability optimizationRepeatability =
        new OptimizationRepeatability<T>(grid, this.progressBar, this.params,
            this.stitchingStatistics);

    if (runSequential)
      optimizationRepeatability.computeGlobalOptimizationRepeatablitySequential();
    else
      optimizationRepeatability.computeGlobalOptimizationRepeatablity();


    if (optimizationRepeatability.isExceptionThrown())
      throw optimizationRepeatability.getWorkerThrowable();


    this.stitchingStatistics.stopTimer(RunTimers.GlobalOptimizationTime);

    Log.msg(LogType.HELPFUL, "Completed Global Optimization in "
            + this.stitchingStatistics.getDuration(RunTimers.GlobalOptimizationTime) + "ms");

    StitchingGuiUtils.updateProgressBar(progressBar, true, "Composing tiles");

    this.stitchingStatistics.startTimer(RunTimers.GlobalPositionTime);
    TileGridUtils.traverseMaximumSpanningTree(grid);

    this.stitchingStatistics.stopTimer(RunTimers.GlobalPositionTime);

    Log.msg(LogType.HELPFUL, "Completed MST in "
        + this.stitchingStatistics.getDuration(RunTimers.GlobalPositionTime) + "ms");

    StitchingGuiUtils.updateProgressBarCompleted(progressBar);

    return true;
  }


  private static <T> void releaseTiles(TileGrid<ImageTile<T>> grid) {
    TileGridUtils.releaseTiles(grid);
  }

  private <T> void outputGrid(TileGrid<ImageTile<T>> grid, final JProgressBar progress,
                              int timeSlice) throws FileNotFoundException {

    if (this.isCancelled)
      return;

    ImagePlus img = null;

    if (this.params.getOutputParams().isOutputFullImage()) {

      img = saveFullImage(grid, progress, timeSlice);

      if (img == null)
        return;
    }

    if (this.params.getOutputParams().isDisplayStitching()) {
      displayFullImage(grid, progress, img);

    }

    StitchingGuiUtils.updateProgressBarCompleted(progress);

    Log.msg(LogType.MANDATORY, "Completed output options for slice " + timeSlice + ".");
  }

  private <T> void outputMeta(TileGrid<ImageTile<T>> grid, final JProgressBar progress,
                              int timeSlice) {

    File metaDir = new File(this.params.getOutputParams().getOutputPath());
    metaDir.mkdirs();

    StitchingGuiUtils.updateProgressBar(progress, true, "Outputting metadata");

    int nDigits = this.params.getInputParams().getNumberTimeSliceDigits();

    // abs positions
    Stitching.outputAbsolutePositions(grid, this.params.getOutputParams().getAbsPosFile(timeSlice, nDigits));

    // relative positions
    Stitching.outputRelativeDisplacements(grid,
        this.params.getOutputParams().getRelPosFile(timeSlice, nDigits));

    // relative positions no optimization
    if (this.params.getOutputParams().isOutputMeta())
      Stitching.outputRelativeDisplacementsNoOptimization(grid, this.params.getOutputParams()
          .getRelPosNoOptFile(timeSlice, nDigits));
  }

  private <T> ImagePlus saveFullImage(TileGrid<ImageTile<T>> grid, final JProgressBar progress,
                                      int timeSlice) throws FileNotFoundException {
    ImagePlus img = null;
    StitchingGuiUtils.updateProgressBar(progress, true, "Writing Full Image");

    int nDigits = this.params.getInputParams().getNumberTimeSliceDigits();

    File imageFile = this.params.getOutputParams().getOutputImageFile(timeSlice, nDigits);

    ImageTile<T> initImg = grid.getSubGridTile(0, 0);
    initImg.readTile();

    int width = TileGridUtils.getFullImageWidth(grid, initImg.getWidth());
    int height = TileGridUtils.getFullImageHeight(grid, initImg.getHeight());

    Log.msg(LogType.MANDATORY, "Writing full image to: " + imageFile.getAbsolutePath()
        + "  Width: " + width + " Height: " + height);

    this.stitchingStatistics.startTimer(RunTimers.OutputFullImageTileTime);
    Blender blend = null;

    try {
      switch (this.params.getOutputParams().getBlendingMode()) {
        case AVERAGE:
          blend = new AverageBlend();
          break;
        case LINEAR:
          initImg.readTile();
          blend =
              new LinearBlend(initImg.getWidth(), initImg.getHeight(), this.params.getOutputParams().getBlendingAlpha());
          break;
        case OVERLAY:
          blend = new OverlayBlend();
          break;
        default:
          break;
      }

      if (blend != null) {
        blend.init(width, height, initImg.getImagePlus());


        imageExporter = new LargeImageExporter<T>(grid, 0, 0, width, height, blend, progress);
        img = imageExporter.exportImage(imageFile);


        this.stitchingStatistics.stopTimer(RunTimers.OutputFullImageTileTime);

        Log.msg(LogType.MANDATORY, "Finished saving full image: " + imageFile.getAbsolutePath());
      } else {
        Log.msg(LogType.MANDATORY,
            "Error: Unable to initialize blending mode: " + this.params.getOutputParams().getBlendingMode());
      }

    } catch (OutOfMemoryError e) {
      Log.msg(LogType.MANDATORY, "Error: Insufficient memory to save image.");
      showError("Out of memory error: " + e.getMessage());

      return null;
    } catch (NegativeArraySizeException e) {
      Log.msg(LogType.MANDATORY, "Error: Java does not support sizes of size width*height > "
          + Integer.MAX_VALUE);
      return null;
    }

    initImg.releasePixels();

    return img;
  }

  private <T> void displayFullImage(TileGrid<ImageTile<T>> grid, final JProgressBar progress,
                                    ImagePlus img) throws FileNotFoundException {
    if (img == null) {
      ImageTile<T> initImg = grid.getSubGridTile(0, 0);
      initImg.readTile();

      int width = TileGridUtils.getFullImageWidth(grid, initImg.getWidth());
      int height = TileGridUtils.getFullImageHeight(grid, initImg.getHeight());

      if (this.isCancelled)
        return;

      Blender blend = null;
      try {
        switch (this.params.getOutputParams().getBlendingMode()) {
          case AVERAGE:
            blend = new AverageBlend();
            break;
          case LINEAR:
            initImg.readTile();
            blend =
                new LinearBlend(initImg.getWidth(), initImg.getHeight(),
                    this.params.getOutputParams().getBlendingAlpha());
            break;
          case OVERLAY:
            blend = new OverlayBlend();
            break;
          default:
            break;

        }
      } catch (OutOfMemoryError e) {
        Log.msg(LogType.MANDATORY, "Error: Insufficient memory to save image.");
        showError(e.getMessage());
        return;
      } catch (NegativeArraySizeException e) {
        Log.msg(LogType.MANDATORY, "Error: Java does not support sizes of size width*height > "
            + Integer.MAX_VALUE);
        return;
      }

      if (blend != null) {

        if (this.isCancelled)
          return;

        try {
          blend.init(width, height, initImg.getImagePlus());

          imageExporter = new LargeImageExporter<T>(grid, 0, 0, width, height, blend, progress);
          img = imageExporter.exportImage(null);
        } catch (OutOfMemoryError e) {
          Log.msg(LogType.MANDATORY, "Error: Insufficient memory for image.");
          showError("Out of memory error: " + e.getMessage());
          return;
        } catch (NegativeArraySizeException e) {
          Log.msg(LogType.MANDATORY, "Error: Java does not support sizes of size width*height > "
              + Integer.MAX_VALUE);
          showError("Error: Java does not support sizes of size width*height > "
              + Integer.MAX_VALUE);

          return;
        }

      } else {
        Log.msg(LogType.MANDATORY,
            "Error: Unable to initialize blending mode: " + this.params.getOutputParams().getBlendingMode());
      }

      initImg.releasePixels();
    }

    if (img == null) {
      Log.msg(LogType.MANDATORY, "Error: Unable to display image.");
    } else {
      img.setTitle(this.params.getOutputParams().getOutFilePrefix() + "Full_Stitching_Image");
      img.show();
    }
  }

  private <T> void previewNoOverlap() throws FileNotFoundException {
    Log.msg(LogType.MANDATORY, "Checking args for preview:");

    if (this.stitchingGUI.checkAndParseGUI(this.params)) {
      Log.msg(LogType.MANDATORY, "Arg check passed");
    } else {
      Log.msg(LogType.MANDATORY, "Stitching parameter check failed. "
          + "Invalid values are highlighted in red");
      return;
    }

    if (!GraphicsEnvironment.isHeadless()) {

      this.stitchingExecutionFrame = new StitchingStatusFrame(this);

      this.progressBar = this.stitchingExecutionFrame.getProgressBar();
      this.progressLabel = this.stitchingExecutionFrame.getProgressLabel();

      this.stitchingExecutionFrame.display();

    }

    if (this.isCancelled)
      return;

    TileGrid<ImageTile<T>> grid = null;
    int timeSlice = 1;

    if (this.params.getInputParams().isTimeSlicesEnabled()) {
      String val =
          JOptionPane.showInputDialog(null, "Please specify which timeslice you "
              + "would like to preview.", "Timeslice User Input", JOptionPane.OK_CANCEL_OPTION);

      if (val == null) {
        Log.msg(LogType.MANDATORY, "Cancelling preview");
        return;
      }

      try {
        timeSlice = Integer.parseInt(val);
      } catch (NumberFormatException e) {
        Log.msg(LogType.MANDATORY, val + " is not a valid timeslice number. (Must be an integer)");
        return;
      }

      try {
        grid = new TileGrid<ImageTile<T>>(this.params, timeSlice, FftwImageTile.class);
      } catch (InvalidClassException e) {
        e.printStackTrace();
      }

      if (grid == null) {
        Log.msg(LogType.MANDATORY, "Error creating tile grid.");
        return;
      }
    } else {
      try {
        grid = new TileGrid<ImageTile<T>>(this.params, FftwImageTile.class);
      } catch (InvalidClassException e) {
        e.printStackTrace();
      }

      if (grid == null) {
        Log.msg(LogType.MANDATORY, "Error creating tile grid.");
        return;
      }
    }

    ImagePlus img = null;

    ImageTile<T> initImg = grid.getSubGridTile(0, 0);
    initImg.readTile();

    int width = grid.getExtentWidth() * initImg.getWidth();
    int height = grid.getExtentHeight() * initImg.getHeight();

    Log.msg(LogType.MANDATORY, "Preparing preview image size: " + width + "x" + height);

    try {
      StitchingGuiUtils.updateProgressBar(this.progressBar, true, "Initializing image buffer...");

      Blender blend = new OverlayBlend();

      blend.init(width, height, initImg.getImagePlus());

      imageExporter = new LargeImageExporter<T>(grid, 0, 0, width, height, blend, this.progressBar);
      img = imageExporter.exportImageNoOverlap(null);

    } catch (OutOfMemoryError e) {
      Log.msg(LogType.MANDATORY, "Error: Insufficient memory for image.");
      showError("Out of memory error: " + e.getMessage());
      return;
    } catch (NegativeArraySizeException e) {
      Log.msg(LogType.MANDATORY, "Error: Java does not support sizes of size width*height > "
          + Integer.MAX_VALUE);
      return;
    }


    if (img == null) {
      Log.msg(LogType.MANDATORY, "Error: Unable to display image.");
    } else {
      img.setTitle(this.params.getOutputParams().getOutFilePrefix() + "Full_Stitching_Image");
      img.show();
    }

    initImg.releasePixels();

    StitchingGuiUtils.updateProgressBarCompleted(this.progressBar);

    Log.msg(LogType.MANDATORY, "Completed output no overlap options for slice " + timeSlice + ".");
  }


  /**
   * Gets the stitching statistics associated with this stitching executor
   *
   * @return the stitching statistics
   */
  public StitchingStatistics getStitchingStatistics() {
    return this.stitchingStatistics;
  }


  private static void showError(String details) {
    Log.msg(LogType.MANDATORY, details);

    if (!GraphicsEnvironment.isHeadless()) {
      JOptionPane.showMessageDialog(null, "Error: Cancelling stitching.\n"
          + "Check log for more details.", "Error", JOptionPane.ERROR_MESSAGE);
    }
  }


  public <T> boolean checkOutputGridMemory(TileGrid<ImageTile<T>> grid)
      throws FileNotFoundException {

    ImageTile<T> tile = grid.getSubGridTile(0, 0);
    tile.readTile();

    long width = TileGridUtils.getFullImageWidth(grid, tile.getWidth());
    long height = TileGridUtils.getFullImageHeight(grid, tile.getHeight());

    long numberPixels = width * height;
    if (numberPixels >= (long) Integer.MAX_VALUE)
      return false;

    long byteDepth = tile.getBitDepth() / 8;

    // Account for the memory required to hold a single image
    // Output image is build by read, copy into output, free sequentially
    long requiredMemoryBytes = tile.getHeight() * tile.getWidth() * byteDepth;

    switch (this.params.getOutputParams().getBlendingMode()) {
      case OVERLAY:
        // requires enough memory to hold the output image
        requiredMemoryBytes += numberPixels * byteDepth; // output image matches bit depth
        break;

      case AVERAGE:
        // Account for average blend data
        if (byteDepth == 3) {
          requiredMemoryBytes +=
              numberPixels * 3 * 4; // sums = new float[this.numChannels][height][width];
          requiredMemoryBytes +=
              numberPixels * 3 * 4; // counts = new int[this.numChannels][height][width];
        } else {
          requiredMemoryBytes +=
              numberPixels * 4; // sums = new float[this.numChannels][height][width];
          requiredMemoryBytes +=
              numberPixels * 4; // counts = new int[this.numChannels][height][width];
        }
        requiredMemoryBytes += numberPixels * byteDepth; // output image matches bit depth

        break;
      case LINEAR:
        // Account for the pixel weights
        requiredMemoryBytes +=
            tile.getHeight() * tile.getWidth()
                * 8; // lookupTable = new double[initImgHeight][initImgWidth];

        // Account for average blend data
        if (byteDepth == 3) {
          requiredMemoryBytes +=
              numberPixels * 8; // pixelSums = new double[this.numChannels][height][width];
          requiredMemoryBytes +=
              numberPixels * 8; // weightSums = new double[this.numChannels][height][width];
        } else {
          requiredMemoryBytes +=
              numberPixels * 8; // pixelSums = new double[this.numChannels][height][width];
          requiredMemoryBytes +=
              numberPixels * 8; // weightSums = new double[this.numChannels][height][width];
        }

        requiredMemoryBytes += numberPixels * byteDepth; // output image matches bit depth
        break;

      default:
        break;
    }

    // pad with 10%
    requiredMemoryBytes *= 1.1;

    return requiredMemoryBytes < Runtime.getRuntime().maxMemory();
  }


}