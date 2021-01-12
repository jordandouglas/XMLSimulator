package poetry.learning;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.apache.commons.math3.analysis.MultivariateFunction;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import beast.core.Description;
import beast.core.Input;
import beast.core.Logger;
import beast.core.StateNode;
import beast.core.util.Log;
import poetry.PoetryAnalyser;
import poetry.sampler.POEM;
import poetry.tools.MinESS;
import weka.classifiers.functions.GaussianProcesses;
import weka.classifiers.functions.supportVector.RBFKernel;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.converters.ArffSaver;



@Description("Samples weights using Gaussian Processes. Prior distribution must be specified.")
public class GaussianProcessSampler extends WeightSampler {

	
	
	public enum AcquisitionFunction {
		EI, POI
	}
	
	final public Input<String> poetryFileInput = new Input<>("poetry", "File to store poetry meta-information in", Input.Validate.REQUIRED);
	final public Input<WeightSampler> priorInput = new Input<>("prior", "Prior weight sampler");
	final public Input<Double> noiseInput = new Input<>("noise", "The noise to use in Gaussian Processes", 0.2);
	final public Input<Double> explorativityInitInput = new Input<>("explorativity", "Initial explorativity in Gaussian Processes for iteration 1", 0.5);
	final public Input<Double> explorativityDecayInput = new Input<>("decay", "Decay in explorativity on each iteration", 0.7);
	final public Input<Double> explorativityMinInput = new Input<>("minExplorativity", "Minimum explorativity after decay", 0.01);
	final public Input<Double> minWeightInput = new Input<>("min", "Minimum value that all operator weights must surpass", 0.01);
	final public Input<Double> priorWeightInput = new Input<>("priorWeight", "The total observational weight of the prior distribution, where each run of this xml file has a weight of 1", 5.0);
	
	final public Input<AcquisitionFunction> acquisitionFunctionInput = new Input<>("acquisition", "The acquisition function for Bayesian optimisation", AcquisitionFunction.EI, AcquisitionFunction.values());
	
	final private static String distClassName = "dist";
	final private static String jsonNtrialsName = "ntrials";
	final private static String jsonSamplesName = "samples";
	final private static String jsonNlogName = "nstates";
	
	
	AcquisitionFunction acquisition;
	
	boolean resumingPoetry;
	WeightSampler prior;
	File poetryFile;
	double minWeight;
	double priorWeight;
	double explorativity;
	
	// Poetry json before 
	JSONObject initialPoetry;
	int iterationNum;
	
	
	@Override
	public void initAndValidate() {
		this.prior = priorInput.get();
		if (this.prior == null) {
			this.prior = new DimensionalSampler();
			this.prior.initByName("scale", -1.0);
		}
		
		// State file
		this.poetryFile = new File(poetryFileInput.get());
		if (this.poetryFile.exists() && !this.poetryFile.canWrite()) {
			throw new IllegalArgumentException("Error: cannot write to " + this.poetryFile.getPath());
		}
		
		
		// Minimum weight
		this.minWeight = this.minWeightInput.get();
		if (this.minWeight <= 0) this.minWeight = 0;
		
		
		// Statistical learning weight for prior
		this.priorWeight = this.priorWeightInput.get();
		if (this.priorWeight <= 0) this.priorWeight = 0;
		
		
		try {
			this.initialPoetry = this.readPoetry();
		} catch (Exception e) {
			e.printStackTrace();
			throw new IllegalArgumentException("Error: encountered problem while parsing " + this.poetryFile.getPath());
		}
		//poetry.get("poetry");
		
		this.acquisition = acquisitionFunctionInput.get();
		
		
		// Resume if there exists a valid state file
		this.iterationNum = 0;
		resumingPoetry = true;
		try {
			if (this.initialPoetry == null ) resumingPoetry = false;
			else if (this.initialPoetry.getJSONArray(jsonSamplesName) == null) resumingPoetry = false;
			else if (this.initialPoetry.getJSONArray(jsonSamplesName).length() == 0) resumingPoetry = false;
			
			if (this.resumingPoetry) {
				this.iterationNum = this.initialPoetry.getInt(jsonNtrialsName);
			}
			
		} catch (JSONException e) {}
		
		if (!this.resumingPoetry) this.initialPoetry = null;
		
		
		
		// Current explorativity
		double initE = this.explorativityInitInput.get();
		double decay = this.explorativityDecayInput.get();
		double finalE = this.explorativityMinInput.get();
		if (this.iterationNum > 0) this.explorativity = Math.max(initE * Math.pow(decay, this.iterationNum-1), finalE);
		else this.explorativity = initE;
		
		
		
	}
	
	
	@Override
	public void initialise(List<POEM> poems, File database, StateNode placeholder, PoetryAnalyser poetry, boolean isMC3) {
		this.prior.initialise(poems, database, placeholder, poetry, isMC3);
		super.initialise(poems, database, placeholder, poetry, isMC3);
	}
	

	@Override
	public void sampleWeights() throws Exception {
		double[] weights = this.sampleWeights(this.poems);
		this.setWeights(weights);
	}

	
	

	@Override
	public double[] sampleWeights(List<POEM> poems) throws Exception {
		
		double[] weights;
		
		
		Log.warning("Starting iteration " + this.iterationNum);
		Log.warning("Bayesian optimisation explorativity = " + this.explorativity);
		
		// Is this the first round?
		if (!this.resumingPoetry) {
			
			Log.warning("Sampling weights using a " + this.prior.getClass().getCanonicalName() + "...");
			
			// Sample from prior
			weights = this.prior.sampleWeights(poems);
			
		}else {
			
			Log.warning("Resuming weight sampling as a Gaussian Process...");
			
			// Update posterior
			weights = this.getGPWeights();
			
		}
		
		
		// Ensure that all weights are above a minimum
		double weightSum = 0;
		for (int i = 0; i < weights.length; i ++) {
			weights[i] = Math.max(weights[i], this.minWeight);
			weightSum += weights[i];
		}
		for (int i = 0; i < weights.length; i ++) weights[i] /= weightSum;
		
		
		return weights;
		
	}
	
	
	
	
	/**
	 * Read in the poetry file
	 * @return
	 * @throws JSONException 
	 * @throws IOException 
	 */
	protected JSONObject readPoetry() throws JSONException, IOException {
		if (!this.poetryFile.exists()) return null;
		String jsonStr = new String (Files.readAllBytes(Paths.get(this.poetryFile.getCanonicalPath())));
		if (jsonStr.isEmpty()) return null;
		JSONObject json = new JSONObject(jsonStr);
		return json;
	}
	
	
	/**
	 * Store this as a json
	 * @return
	 * @throws Exception 
	 */
	public JSONObject getPoetryJSON() throws Exception {
		
		JSONObject json = new JSONObject();
		
		// How many trials?
		int ntrials = 0; //this.initialPoetry == null ? 1 : this.initialPoetry.getInt(jsonNtrialsName) + 1;
		
		// Create samples array
		JSONArray samples = new JSONArray();
		
		
		
		// Append prior information to array
		if (this.initialPoetry != null) {
			JSONArray samplesInit = (JSONArray) this.initialPoetry.get(jsonSamplesName);
			for (int i = 0; i < samplesInit.length(); i ++) {
				
				// The last session is the current session if resuming
				if (Logger.FILE_MODE == Logger.LogFileMode.resume && i == samplesInit.length()-1) {
					break;
				}
				
				samples.add(samplesInit.get(i));
				ntrials ++;
			}
		}
		
		
		// Add the current state
		JSONObject sampleThis = this.getCurrentStateJSON();
		if (sampleThis != null) {
			samples.add(sampleThis);
			ntrials ++;
		}
		
		
		// Add to json
		json.put(jsonNtrialsName, ntrials);
		json.put(jsonSamplesName, samples);
		
		return json;
	}
	
	
	/**
	 * Current state as a JSON
	 * @return
	 * @throws Exception
	 */
	public JSONObject getCurrentStateJSON()  throws Exception {
		
		JSONObject sampleThis = new JSONObject();

		// Store the weights
		for (POEM poem : this.poems) {
			sampleThis.put(poem.getWeightColname(), poem.getWeight());
		}
		
		// Calculate fractional ESSes
		double ESSsum = 0;
		double[] ESSes = new double[this.poems.size()];
		int meanNLogs = 0;
		MinESS calculator;
		try {
			for (int i = 0; i < this.getNumPoems(); i ++) {
				POEM poem = this.poems.get(i);
				calculator = new MinESS(new File(poem.getLoggerFileName()), "treePrior"); //tmp
				calculator.run();
				if (calculator.getNLogs() < 10) return null;
				double ess = calculator.getMeanESS();
				//double ess = calculator.getMinESS();
				ESSes[i] = ess;
				ESSsum += ESSes[i];
				meanNLogs += calculator.getNLogs();
			}
		} catch(Exception e) {
			return null;
		}
		meanNLogs = meanNLogs / ESSes.length;
		
		// Store the fractional ESSes
		for (int i = 0; i < this.getNumPoems(); i ++) {
			POEM poem = this.poems.get(i);
			sampleThis.put(poem.getESSColname(), ESSes[i]/ESSsum);
		}
		
		// N logged states
		sampleThis.put(jsonNlogName, meanNLogs);
		
		
		// Store the distance
		double targetFractionalESS = 1.0 / this.getNumPoems();
		double totalDist = 0;
		for (int i = 0; i < this.getNumPoems(); i ++) {
			double dist = Math.pow(ESSes[i]/ESSsum - targetFractionalESS, 2);
			totalDist += dist;
		}
		totalDist = Math.sqrt(totalDist);
		sampleThis.put(distClassName, totalDist);
		
		
		
		return sampleThis;
		
	}
	
	
	/**
	 * Update poetry state file
	 * @throws IOException 
	 */
	@Override
	public void log() throws Exception {
		
		// Build json
		JSONObject jsonOut = this.getPoetryJSON();
		
		// (Over)write to file
		FileWriter fw = new FileWriter(this.poetryFile);
		fw.write(jsonOut.toString());
		fw.close();
		
	}
	
	

	

	/**
	 * Select weights using gaussian process
	 * @return
	 * @throws Exception
	 */
	public double[] getGPWeights() throws Exception {
		
		
		// Create Instances object
		ArrayList<Attribute> attributes = new ArrayList<>();
		for (int i = 0; i < this.getNumPoems()-1; i ++) {
			Attribute tweight = new Attribute("tweight" + i);
			attributes.add(tweight);
		}
		attributes.add(new Attribute(distClassName));
		int nattr = attributes.size();
		Instances instances = new Instances("dirichlet", attributes,  attributes.size());
		instances.setClass(instances.attribute(distClassName));
			
		// Read in json file and add instances
		JSONArray previousTrials = this.initialPoetry.getJSONArray(jsonSamplesName);
		for (int i = 0; i < previousTrials.length(); i ++) {
			
			
			JSONObject json = previousTrials.getJSONObject(i);
			
			// One instance per row in database
			Instance instance = new DenseInstance(nattr);
			instance.setDataset(instances);
			
			// One column per weight
			double[] weights = new double[this.poems.size()];
			for (int p = 0; p < this.getNumPoems(); p ++) {
				POEM poem = this.poems.get(p);
				String weightCol = poem.getWeightColname(); // + ".d";
				if (json.get(weightCol) == null) {
					throw new Exception("Cannot find column " + weightCol + " in poetry file");
				}
				double weight = json.getDouble(weightCol);
				weights[p] = weight;
				
			}
			
			
			// Set breaks
			double[] tweights = breakSticks(weights);
			for (int p = 0; p < this.getNumPoems()-1; p ++) {
				instance.setValue(instances.attribute("tweight" + p), tweights[p]);
			}
			
			// Class value (distance) as Pmean
			if (json.get(distClassName) == null) {
				throw new Exception("Cannot find column " + distClassName + " in poetry file");
			}
			double dist = json.getDouble(distClassName);
			dist = Math.log(dist);
			instance.setValue(instances.attribute(distClassName), dist);
			instances.add(instance);
			
		}
		
		
		
		// Add prior database?
		if (this.prior instanceof BayesianDecisionTreeSampler && this.priorWeight > 0) {
			
			// Load the database
			BayesianDecisionTreeSampler treeSampler = (BayesianDecisionTreeSampler) this.prior;
			treeSampler.sampleWeights(poems);
			Instances priorDatabase = treeSampler.getDatabaseAtLeaf();
			
			Log.warning("Adding " + priorDatabase.size() + " prior instances to GP model with a total weight of " + this.priorWeight);
			
			// Add the instances to the main list of instances, but with a smaller learning weight
			double weightPerInst = this.priorWeight / priorDatabase.size();
			for (int i = 0; i < priorDatabase.size(); i++) {
				Instance priorInst = priorDatabase.get(i);
				priorInst.setWeight(weightPerInst);
				instances.add(priorInst);
			}
			
			
			
		}
		
		
		/*
		// Save the dataset
		ArffSaver saver = new ArffSaver();
		saver.setInstances(instances);
		saver.setFile(new File("/home/jdou557/Documents/Marsden2019/Months/December2020/BDT/kernel30.arff"));
		saver.writeBatch();
		*/
		
		
		// Train the kernel
		// No normalisation or standardisation. RBFKernel. Noise
		GaussianProcesses kernel = new GaussianProcesses();
		kernel.setOptions(new String[] { "-N", "2", "-K", RBFKernel.class.getCanonicalName(), "-L", "" + this.noiseInput.get() });
		kernel.buildClassifier(instances);
		
		
		// What is the smallest observed mean so far?
		double bestMean = Double.POSITIVE_INFINITY;
		double[] bestTweights = new double[this.poems.size()-1];
		for (int i = 0; i < instances.size(); i ++) {
			double val = instances.get(i).value(instances.attribute(distClassName));
			if (val < bestMean) {
				for (int p = 0; p < this.getNumPoems()-1; p ++) {
					bestTweights[p] = instances.get(i).value(instances.attribute("tweight" + p));
				}
				bestMean = val;
			}
		}
		String msg = "Cumulative optimal value " + bestMean + ", with weights";
		double[] bestWeights = repairSticks(bestTweights);
		for (double w : bestWeights) msg += " " + w;
		Log.warning(msg);
		
		// Optimise the expected improvement function (minimisation)
		MultivariateFunction fn = null;
		switch (this.acquisition) {
			case EI:{
				fn = new ExpectedImprovementFunction(instances, kernel, bestMean, this.explorativity);
				Log.warning("Computing maximum expected improvement...");
				break;
			}
			
			case POI:{
				fn = new ProbabilityOfImprovement(instances, kernel, bestMean, this.explorativity);
				Log.warning("Computing maximum probability of improvement...");
				break;
			}
		
		}
		
		
		// Optimise acquisition function to get next iteration's weights
		double[] opt = optimiseSimplex(fn, this.poems.size());
		double[] weights = repairSticks(opt);
		System.out.print(this.acquisition.toString() + " max: ");
		for (double o : weights) System.out.print(o + ", ");
		System.out.println(" eval: " + fn.value(opt));
		
		
		return weights;
		
	}
	
	

	
	/**
	 * Get the probability of improvement
	 *
	 */
	private class ProbabilityOfImprovement implements MultivariateFunction {

		
		 double epsilon;
		 GaussianProcesses gp;
		 NormalDistribution normal;
		 double bestMean;
		 Instances instances;
		 Instance instance;
		
		public ProbabilityOfImprovement(Instances instances, GaussianProcesses gp, double bestMean, double epsilon) {
			 this.epsilon = epsilon;
			 this.instance = new DenseInstance(instances.numAttributes());
			 this.instances = instances;
			 this.bestMean = bestMean;
			 instance.setDataset(instances);
			 this.gp = gp;
		 }
		
		
		@Override
		public double value(double[] tweights) {
			
			// Set the transformed weight (ie the broken stick)
			for (int j = 0; j < tweights.length; j ++) {
				instance.setValue(instances.attribute("tweight" + j), tweights[j]);
			}
			
			// Get mean and standard deviation (in log space)
			double sd, mean;
			try {
				sd = gp.getStandardDeviation(instance);
				mean = gp.classifyInstance(instance);
			} catch (Exception e1) {
				e1.printStackTrace();
				return Double.POSITIVE_INFINITY;
			}
			
			double delta = mean - (bestMean + epsilon);
			normal = new NormalDistribution(mean, sd);
			
			// Want to minimise this term (= 1 - POI). This is equivalent to maximising POI
			double x = delta/sd;
			double poi = normal.cumulativeProbability(x);
			return poi;
			
		}
		
		
		
		
	}
	
	
	
	/**
	 * Get the expected improvement of the weights (ie. expected value above the bestMean)
	 *
	 */
	 private class ExpectedImprovementFunction implements MultivariateFunction {
		 
		 double epsilon;
		 GaussianProcesses gp;
		 NormalDistribution normal;
		 double bestMean;
		 Instances instances;
		 Instance instance;
		 
		 public ExpectedImprovementFunction(Instances instances, GaussianProcesses gp, double bestMean, double epsilon) {
			 this.epsilon = -Math.abs(epsilon);
			 this.instance = new DenseInstance(instances.numAttributes());
			 this.instances = instances;
			 this.bestMean = bestMean;
			 instance.setDataset(instances);
			 this.gp = gp;
		 }
		 
		 
		 public double getExpectedVal(double[] tweights) {
			 
			 double expectedImprovement = this.value(tweights);
			 //Log.warning("Expected improvement " + expectedImprovement);
			 //Log.warning("Best mean " + this.bestMean);
			 //Log.warning("Epsilon " + this.epsilon);
			// Log.warning("Expected value " + expectedImprovement + (this.bestMean + this.epsilon));
			 
			 return expectedImprovement + (this.bestMean);/// + this.epsilon);
		 }
		 
		 
		 @Override
		 public double value(double[] tweights) {
			 
			// Set the transformed weight (ie the broken stick)
			for (int j = 0; j < tweights.length; j ++) {
				instance.setValue(instances.attribute("tweight" + j), tweights[j]);
			}
			
			
			// Get mean and standard deviation (in log space)
			double sd, mean;
			try {
				sd = gp.getStandardDeviation(instance);
				mean = gp.classifyInstance(instance);
			} catch (Exception e1) {
				e1.printStackTrace();
				return Double.POSITIVE_INFINITY;
			}
			
			double delta = mean - (bestMean + epsilon);
			//delta = -delta; // Minimise
			normal = new NormalDistribution(mean, sd);
			
			 
			// Expected value
			double e = delta * normal.cumulativeProbability(delta / sd) + sd*normal.density(delta / sd);
			return e;
			
		 }
		 
		 
	 }


	

	
	
	
}
