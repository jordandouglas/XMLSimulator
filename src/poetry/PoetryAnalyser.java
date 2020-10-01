package poetry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Scanner;

import org.apache.commons.math3.random.EmpiricalDistribution;

import beast.core.Input;
import beast.core.Runnable;
import beast.core.parameter.Map;
import beast.core.util.ESS;
import beast.core.util.Log;
import beast.util.LogAnalyser;
import poetry.sampler.POEM;
import poetry.util.Lock;
import poetry.util.RuntimeLoggable;


/**
 * Reads in poems, a log file, and a database
 * Computes the ESS of any parameters in the log file which are specified by the poems, and inserts them into the database
 * The xml for this file is automatically generated by the SimulateXML class
 * @author jdou557
 *
 */
public class PoetryAnalyser extends Runnable {

	
	final public Input<File> databaseFileInput = new Input<>("database", "The location of a the database (tsv)", Input.Validate.REQUIRED);
	final public Input<Integer> sampleNumInput = new Input<>("number", "The row number in the database of this sample", Input.Validate.REQUIRED);
	final public Input<List<POEM>> poemsInput = new Input<>("poem", "A map between operators and log outputs", new ArrayList<>());
	final public Input<File> runtimeLoggerInput = new Input<>("runtime", "Lof file containing runtimes");
	final public Input<Integer> burninInput = new Input<>("burnin", "Burnin percentage for ESS computation (default 10)", 10);

	
	
	
	int sampleNum;
	int rowNum;
	File database;
	File runtimeLogfile;
	List<POEM> poems;
	HashMap<String, String[]> db;
	
	@Override
	public void initAndValidate() {
		
		this.sampleNum = sampleNumInput.get();
		this.database = databaseFileInput.get();
		this.poems = poemsInput.get();
		this.runtimeLogfile = runtimeLoggerInput.get();
		this.rowNum = -1;
		
		//if (this.poems.isEmpty()) throw new IllegalArgumentException("Please provide at least 1 poem");
		
		// File validation
		if (!this.database.exists()) throw new IllegalArgumentException("Could not locate database " + this.database.getPath());
		if (this.runtimeLogfile != null && !this.runtimeLogfile.exists()) throw new IllegalArgumentException("Could not locate runtime log " + this.runtimeLogfile.getPath());
		
		
	}

	@Override
	public void run() throws Exception {
		
		//smoothRuntime, double rawRuntime
		
		// Runtime 
		int nstates = 0;
		double smoothRuntime = 0, rawRuntime = 0;
		if (this.runtimeLogfile != null) {
			Log.warning("Computing runtime...");
			LogAnalyser analyser = new LogAnalyser(this.runtimeLogfile.getAbsolutePath(), burninInput.get(), true, null); 
			
			// nstates
			Double[] sampled = analyser.getTrace("Sample");
			nstates = (int) Math.floor(sampled[sampled.length-1]);
			int nsamples = nstates / (int) (sampled[sampled.length-1] - sampled[sampled.length-2]);
			System.out.println("There are " + nstates + " states and " + nsamples + " samples");
			
			
			// Actual runtime
			Double[] cumulative = analyser.getTrace(RuntimeLoggable.getCumulativeColname());
			rawRuntime = cumulative[cumulative.length-1] / 3600;
			
			// Smooth runtime
			smoothRuntime = getSmoothRuntime(analyser.getTrace(RuntimeLoggable.getIncrementalColname()), nsamples); //cumulative[cumulative.length-1]  / 3600;
			smoothRuntime = smoothRuntime / 3600000;
			Log.warning("Total runtime (raw) is " + rawRuntime + "hr and runtime (smoothed) is " + smoothRuntime + "hr");
			
		}
		
	
		
		// Open logfile using loganalyser
		Log.warning("Computing ESSes...");
		
		// Calculate ESS for each POEM
		for (POEM poem : this.poems) {
			
			File logFile = new File(poem.getLoggerFileName());
			if (!logFile.exists() || !logFile.canRead()) throw new IllegalArgumentException("Could not locate/read logfile " + logFile.getPath());
			LogAnalyser analyser = new LogAnalyser(logFile.getAbsolutePath(), this.burninInput.get(), true, null);
			
			
			// Get minimum ESS across all relevant column names
			double minESS = Double.POSITIVE_INFINITY;
			for (String colname : analyser.getLabels()) {

				double ESS = analyser.getESS(colname);
				if (ESS < 0 || Double.isNaN(ESS)) continue;
				minESS = Math.min(minESS, ESS);
			}
			System.out.println(poem.getID() + " has a minimum ESS of " + (int) minESS);
			
			poem.setMinESS(minESS);
			
			
			// Put in database array
			//this.setValueAtRow(poem.getESSColname(), "" + minESS);
			
		}
		
		double[] ESSstats = POEM.getESSStats(this.poems);
		System.out.println("ESS coefficient of variation is " + ESSstats[2]);
		
		
		
		// Save the new row to the database. This will lock the database file to avoid conflicts
		Log.warning("Saving to database...");
		this.updateDatabase(ESSstats, smoothRuntime, rawRuntime, nstates);
		
		
		Log.warning("Done!");
		
	}
	
	
	/**
	 * Same as getSmoothRuntime(double[] incrTrace) except this works on Double[])
	 * @param incrTrace - a Double array with capital D
	 * @param nsamples - number of time samples (including burnin)
	 * @return
	 */
	public static double getSmoothRuntime(Double[] incrTrace, int nsamples) {
		
		// Convert Double[] to double[]
		double[] arr = new double[incrTrace.length];
		for (int i = 0; i < arr.length; i ++) {
			arr[i] = (double) incrTrace[i];
		}
		return getSmoothRuntime(arr, nsamples);
	}
	
	/**
	 * Calculates the total runtime of this system by multiplying the 
	 * mode incremental runtime by the number of reported times.
	 * This can help when a cluster is used and thread interrupting is common
	 * @param incrTrace - a double array with little d
	 * @param nsamples - number of time samples (including burnin)
	 * @return
	 */
	public static double getSmoothRuntime(double[] incrTrace, int nsamples) {
		
		
		if (incrTrace == null || incrTrace.length == 0) return 0;
		final int nbins = 1 + (int) Math.sqrt(incrTrace.length); // incrTrace.length / 2;

		
		// Build kernel density estimation
		EmpiricalDistribution kde = new EmpiricalDistribution(nbins);
		kde.load(incrTrace);
		
		
		double y = kde.density(4500.0);
		
		double xMax = 0; // x-value of mode
		double pMax = 0; // density of mode
		
		// Find the mode by iterating through quantiles
		final int nquant = 1000;
		for (int i = 1; i < nquant; i++) {
			
			
			
			double quantile = 1.0 * i/nquant;
			try {
				
				double x = kde.inverseCumulativeProbability(quantile);
				double p = kde.density(x);
			
			//	System.out.println(quantile + "\t" + x + "\t" + p);
				
				// Found the mode?
				if (p > pMax) {
					pMax = p;
					xMax = x;
				}
				
			} catch (Exception e) {
				
			}
			
		}
		
		
		// Assume that the incremental runtime of the mode is the true mean 
		// if there were no interruptions
		//System.out.println("Modal time per sample: " + xMax);
		return xMax * nsamples;
		
	}
	
	
	private void updateDatabase(double[] ESSstats, double smoothRuntime, double rawRuntime, int nstates) throws Exception {
		
		
		
		
		// Lock the database
		Lock lock = new Lock(this.sampleNum, this.database);
		lock.lock();
		
		try {
			
			RandomAccessFile raf = new RandomAccessFile(this.database, "rw");
			FileChannel channel = raf.getChannel();
			//FileLock lock = channel.tryLock();
			
	
			// Open database and find the right line
			this.db = this.openDatabase();
			this.rowNum = this.getRowNum(this.sampleNum);
			if (this.rowNum < 0) throw new Exception("Cannot locate sample number " + this.sampleNum + " in the 'xml' column of the database");
			
			
			//Thread.sleep(2000);
			
			
			// Write ESSes
			for (POEM poem : poems) {
				this.setValueAtRow(poem.getESSColname(), "" + poem.getMinESS());
			}
			
			// Save coefficient of variation
			this.setValueAtRow(POEM.getMeanColumnName(), "" + ESSstats[0]);
			this.setValueAtRow(POEM.getStddevColumnName(), "" + ESSstats[1]);
			this.setValueAtRow(POEM.getCoefficientOfVariationColumnName(), "" + ESSstats[2]);
			
			// Number of states
			this.setValueAtRow(POEM.getNumberOfStatesColumnName(), "" + nstates);
			
			// Save runtime
			this.setValueAtRow(POEM.getRuntimeSmoothColumn(), "" + smoothRuntime);
			this.setValueAtRow(POEM.getRuntimeRawColumn(), "" + rawRuntime);
			
			
			// Get database string
			String out = "";
			for (String colname : this.db.keySet()) out += colname + "\t";
			out += "\n";
			int nrow = this.db.get("xml").length;
			for (int rowNum = 0; rowNum < nrow; rowNum ++) {
				for (String colname : this.db.keySet()) {
					out += this.db.get(colname)[rowNum] + "\t";
				}
				out += "\n";
			}
			
			
			// Clear the file and rewrite it 
			channel.truncate(0);
			raf.write(out.getBytes());
			//lock.close();
			
			
		} catch(Exception e) {
			lock.unlock();
			throw e;
		}
		
		
		// Unlock the database
		lock.unlock();
		
		
		
	}

	

	/**
	 * Gets the value at the specified column for this sampleNum in the database
	 * @param colname
	 * @return
	 */
	public String getValueAtRow(String colname) throws Exception {
		if (this.db == null || this.rowNum < 0) return null;
		if (!db.containsKey(colname)) throw new Exception("Cannot locate column " + colname + " in the database");
		String[] vals = db.get(colname);
		return vals[this.rowNum];
	}
	
	
	/**
	 * Set a value within this row of the database array
	 * @param colname
	 * @param value
	 */
	public void setValueAtRow(String colname, String value) throws Exception {
		if (this.db == null || this.rowNum < 0) return;
		if (!db.containsKey(colname)) throw new Exception("Cannot locate column " + colname + " in the database");
		String[] vals = db.get(colname);
		vals[this.rowNum] = value;
	}
	
	
	/**
	 * Gets the row number associated with this sample number
	 * @param sampleNum
	 * @return
	 */
	public int getRowNum(int sampleNum) {
		
		if (this.db == null) return -1;
		
		String sampleStr = "" + sampleNum;
		String[] ids = db.get("xml");
		for (int i = 0; i < ids.length; i ++) {
			if (sampleStr.equals(ids[i])) {
				return i;
			}
		}
		
		
		return -1;
		
	}
	
	
	
	/**
	 * Opens the database and returns it as a hashmap
	 * @return
	 * @throws Exception
	 */
	public LinkedHashMap<String, String[]> openDatabase() throws Exception {
		
		LinkedHashMap<String, String[]> map = new LinkedHashMap<String, String[]>();
		
		// Read headers
		Scanner scanner = new Scanner(this.database);
		String[] headers = scanner.nextLine().split("\t");
		int ncol = headers.length;
		List<String[]> values = new ArrayList<String[]>();
		
		// Read in data
		int lineNum = 1;
		while (scanner.hasNextLine()) {
			
			lineNum ++;
			String line = scanner.nextLine();
			if (line.isEmpty()) continue;
			String[] spl = line.split("\t");
			if (spl.length != ncol) throw new IllegalArgumentException("Database has " + spl.length + " elements on file line " + lineNum + " but there should be " + ncol);
			values.add(line.split("\t"));
			
		}
		
		
		// Build map
		int nrow = values.size();
		for (int colNum = 0; colNum < ncol; colNum ++) {
			String colname = headers[colNum];
			String[] vals = new String[nrow];
			for (int rowNum = 0; rowNum < nrow; rowNum ++) {
				String[] row = values.get(rowNum);
				String val = row[colNum];
				vals[rowNum] = val;
			}
			map.put(colname, vals);
		}
		
		
		// Check the xml column exists
		if (!map.containsKey("xml")) throw new IllegalArgumentException("Cannot locate 'xml' column in database");
		
		scanner.close();
		
		return map;
		
	}
	

}











