package poetry.learning;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

import org.apache.commons.math3.analysis.MultivariateFunction;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.optim.InitialGuess;
import org.apache.commons.math3.optim.MaxEval;
import org.apache.commons.math3.optim.MaxIter;
import org.apache.commons.math3.optim.PointValuePair;
import org.apache.commons.math3.optim.linear.LinearConstraint;
import org.apache.commons.math3.optim.linear.LinearConstraintSet;
import org.apache.commons.math3.optim.linear.Relationship;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;
import org.apache.commons.math3.optim.nonlinear.scalar.ObjectiveFunction;
import org.apache.commons.math3.optim.nonlinear.scalar.noderiv.NelderMeadSimplex;
import org.apache.commons.math3.optim.nonlinear.scalar.noderiv.SimplexOptimizer;

import beast.core.Input;
import beast.core.util.Log;
import beast.evolution.alignment.Alignment;
import beast.evolution.tree.Tree;
import beast.util.Randomizer;
import poetry.PoetryAnalyser;
import poetry.decisiontree.DecisionNode;
import poetry.decisiontree.DecisionTree;
import poetry.decisiontree.DecisionTreeDistribution.ResponseMode;
import poetry.sampler.POEM;
import poetry.util.BEAST2Weka;
import weka.classifiers.functions.GaussianProcesses;
import weka.classifiers.functions.supportVector.RBFKernel;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.converters.ArffSaver;

public class BayesianDecisionTreeSampler extends WeightSampler {

	
	final public Input<Tree> treeInput = new Input<>("tree", "The phylogenetic tree (used for machine learning)", Input.Validate.REQUIRED);
	final public Input<Alignment> dataInput = new Input<>("data", "The alignment (used for machine learning)", Input.Validate.REQUIRED);
	
	final public Input<ResponseMode> regressionInput = new Input<>("regression", "Regression model at the leaves", ResponseMode.test, ResponseMode.values());
	
	final public Input<List<ModelValue>> modelValuesInput = new Input<>("model", "List of models and their values -- for applying the decsion tree from the model", new ArrayList<>());
	final public Input<String> treesInput = new Input<>("trees", "A file containing decision trees", Input.Validate.REQUIRED);
	
	
	final private static String distClassName = "dist";
	
	
	@Override
	public void initAndValidate() {
		
		
		
		
	}

	@Override
	public void sampleWeights() throws Exception {
		double[] weights = this.sampleWeights(poems);
		this.setWeights(weights);
	}
	
	

	@Override
	public double[] sampleWeights(List<POEM> poems) throws Exception {

		
		
		// Model values
		List<ModelValue> modelValues = modelValuesInput.get();
		
		// Get the Weka Instance of this session
		Instances instances = BEAST2Weka.getInstance(dataInput.get(), treeInput.get(), this, modelValues);
				
		int dim = poems.size();
		double[] weights = new double[dim];
		
		
		
		
		// Load decision tree
		List<DecisionTree> trees = parseDecisionTrees(new File(treesInput.get()), 10);
		DecisionTree decisionTree = trees.get(trees.size()-1);
		decisionTree.setRegressionMode(regressionInput.get());
		System.out.println("tree : " + decisionTree.toString());
		
		
		weights = this.getWeights(trees, instances);
		
		return weights;
		
	}
	
	

	/**
	 * Take the optimal weights from a sample in the posterior
	 * @param trees
	 * @param inst
	 * @return
	 * @throws Exception
	 */
	protected double[] getWeights(List<DecisionTree> trees, Instances inst) throws Exception {
		
		double[] weights = new double[this.poems.size()];
		
		int ntaxa = (int)(inst.instance(0).value(inst.attribute("ntaxa")));
		
		SquaredAlphaDistance[] fns = new SquaredAlphaDistance[trees.size()];
		
		for (int treeNum = 0; treeNum < trees.size(); treeNum ++) {
				
			DecisionTree tree = trees.get(treeNum);
			DecisionNode leaf = tree.getLeaf(inst);
			
			// Optimise
			double tau = Double.parseDouble(leaf.getToken("sigma"));
			double target = tau / this.getNumPoems();
			double[] slopes = new double[this.poems.size()];
			double[] intercepts = new double[this.poems.size()];
			double[] dims  = new double[this.poems.size()];
			for (int i = 0; i < this.getNumPoems(); i ++) {
				POEM poem = this.poems.get(i);
				
				
				String slopeStr = leaf.getToken("slope_" + poem.getESSColname() + ".p");
				String interceptStr = leaf.getToken("intercept_" + poem.getESSColname() + ".p");
				double slope = Double.parseDouble(slopeStr);
				double intercept = Double.parseDouble(interceptStr);
				double dim = BEAST2Weka.getDimension(poem.getID(), poem.getDim(), ntaxa); 
				
				slopes[i] = slope;
				intercepts[i] = intercept;
				dims[i] = dim;
				
				
				if (treeNum == trees.size()-1) {
					System.out.println(poem.getID() + " slope " + slope + " intercept " + intercept + " target " + target);
				}
				
				
				
			}
			
			
			SquaredAlphaDistance fn = new SquaredAlphaDistance(slopes, intercepts, dims, tau);
			fns[treeNum] = fn;
			
			if (treeNum == trees.size()-1) {
				
				double[] opt = optimiseSimplex(fn, fn.getDimension());
				weights = repairSticks(opt);
				System.out.print("max: ");
				for (double o : weights) System.out.print(o + ", ");
				System.out.println(" eval: " + fn.value(opt));
				
				System.out.print("alpha: ");
				for (double o : fn.getAlpha(opt)) System.out.print(o + ", ");
				System.out.println();
				
			}
			
		}

		
		
		//buildEpsilonDistribution(fns, this.poems);
		
		return weights;
	}
	
	

	
	
	public static void buildEpsilonDistribution(SquaredAlphaDistance[] fns, List<POEM> poems) throws Exception {
		

		final int nsamples = 1000;
		
		
		Log.warning("Building kernel density...");
		

		
		// Create Instances object
		ArrayList<Attribute> attributes = new ArrayList<>();
		for (int i = 0; i < fns[0].getDimension()-1; i ++) {
			Attribute tweight = new Attribute("tweight" + i);
			attributes.add(tweight);
		}
		attributes.add(new Attribute(distClassName));
		int nattr = attributes.size();
		Instances instances = new Instances("dirichlet", attributes,  attributes.size());
		instances.setClass(instances.attribute(distClassName));
		
		
		
		
		// Sample weights using a dirichlet on the poem alphas
		boolean isNaN = false;
		for (int i = 0; i < 2*nsamples; i ++) {
			
			SquaredAlphaDistance fn = fns[Randomizer.nextInt(fns.length)];
			
			double[] tweights;
			if (i >= nsamples) {
				
				// Include the optimal weight with some jitter
				tweights = optimiseSimplex(fn, fn.getDimension());
				
				
				// Add some random jitter
				for (int j = 0; j < fn.getDimension()-1; j ++) {
					tweights[j] = tweights[j] + Randomizer.nextGaussian()*Randomizer.nextExponential(5);
					//if (true || i != 2*nsamples -1)tweights[j] += Randomizer.nextGaussian()*Randomizer.nextExponential(5);
				}
				
				
				
			}else {
				
				double[] weights = null;//TODO DimensionalSampler.sampleWeights(poems, Randomizer.nextExponential(0.1));
				double weightSum = 0;
				for (int j = 0; j < fn.getDimension(); j ++) {
					weights[j] += 0.00001; // Prevent numerical instabilities from tiny weights
					weightSum += weights[j];
				}
				for (int j = 0; j < fn.getDimension(); j ++) weights[j] /= weightSum;
				//double[] weights = DirichletSampler.sampleWeights(poems);
				tweights = breakSticks(weights);
				
				
				// Check for numerical instabilities
				for (int j = 0; j < fn.getDimension()-1; j ++) {
					//System.out.println("weight " + weights[j] + " tweight " + tweights[j]);
					if (Double.isNaN(tweights[j])) isNaN = true;
				}
				//System.out.println("weight final " + weights[fn.getDimension()-1]);
			}
			
			Instance instance = new DenseInstance(nattr);
			instance.setDataset(instances);
			
			// Set the transformed weight (ie the broken stick)
			for (int j = 0; j < fn.getDimension()-1; j ++) {
				instance.setValue(instances.attribute("tweight" + j), tweights[j]);
			}
			//System.out.println("weight final " + weights[fn.getDimension()-1]);
			
			// Compute the distance in logspace
			double dist = fn.value(tweights);
			if (isNaN || Double.isNaN(dist)) continue;
			dist = Math.log(dist);
			instance.setValue(instances.attribute(distClassName), dist);
			instances.add(instance);
			
		}
		
		
		Log.warning(instances.size() + " kernel samples");
		SquaredAlphaDistance fn = fns[fns.length-1];
		
		
		// Save the dataset
		ArffSaver saver = new ArffSaver();
		saver.setInstances(instances);
		saver.setFile(new File("/home/jdou557/Documents/Marsden2019/Months/December2020/BDT/kernel.arff"));
		saver.writeBatch();
		
		
		
		// Train the kernel
		// No normalisation or standardisation. RBFKernel
		GaussianProcesses kernel = new GaussianProcesses();
		kernel.setOptions(new String[] { "-N", "2", "-K", RBFKernel.class.getCanonicalName() });
		kernel.buildClassifier(instances);
		
		
		
		Instance instance = new DenseInstance(nattr);
		instance.setDataset(instances);
		double[] tweights = optimiseSimplex(fn, fn.getDimension()); // fn.breakSticks(DirichletSampler.sampleWeights(poems));
		
		// Set the transformed weight (ie the broken stick)
		for (int j = 0; j < fn.getDimension()-1; j ++) {
			System.out.println("tweight " + tweights[j]);
			instance.setValue(instances.attribute("tweight" + j), tweights[j]);
		}
		
		
		double sd = kernel.getStandardDeviation(instance);
		double mean = kernel.classifyInstance(instance);
		System.out.println("opt mean: " + mean + " sd: " + sd + " real space val " + Math.exp(mean) + " true " + fn.value(tweights));
		
		
		
		tweights = null;// TODO breakSticks(DimensionalSampler.sampleWeights(poems, 20));
		
		// Set the transformed weight (ie the broken stick)
		for (int j = 0; j < fn.getDimension()-1; j ++) {
			instance.setValue(instances.attribute("tweight" + j), -1.0);
		}
		
		
		sd = kernel.getStandardDeviation(instance);
		mean = kernel.classifyInstance(instance);
		System.out.println("rand mean: " + mean + " sd: " + sd + " real space val " + Math.exp(mean) + " true " + fn.value(tweights));
		
	}
	

	
	/**
	 * Parse decision trees from a newick file
	 * @param newickFile
	 * @return
	 * @throws IOException
	 */
	public static List<DecisionTree> parseDecisionTrees(File newickFile, int burnin) throws IOException {
		BufferedReader fin = new BufferedReader(new FileReader(newickFile));
		List<DecisionTree> trees = new ArrayList<>();
		
		// Parse trees from newick strings
		String str = null;
        while (fin.ready()) {
            str = fin.readLine();
            if (!str.matches("\\s*")) {
	            DecisionTree tree = new DecisionTree();
	            tree.fromNewick(str);
	            trees.add(tree);
            }
            
        }
		fin.close();  
		
		
		// Burnin
		int desiredLen = (int)Math.ceil(1.0 * trees.size() * burnin / 100);
		while (trees.size() > desiredLen) trees.remove(0);
		
		return trees;
	}
	
	

	 
	 /**
	  * A function which can be optimised using a MultivariateOptimizer
	  * @author jdou557
	  *
	  */
	 private static class SquaredAlphaDistance implements MultivariateFunction {

		 
		 int ndim;
		 double tau;
		 double[] ehalf, emax, dims;
		 double[] alpha;
		 double alphaSum, squaredDistance;
		 double target;
		 
		 public SquaredAlphaDistance(double[] ehalf, double[] emax, double[] dims, double tau) {
			 this.ehalf = ehalf;
			 this.emax = emax;
			 this.dims = dims;
			 this.tau = tau;
			 this.ndim = emax.length;
			 this.alpha = new double[this.ndim];
			 this.target = this.tau / this.ndim;
		 }
		 
		
	
		 
		 public double[] getAlpha(double[] breaks) {
			 
			 
			 double[] weights = repairSticks(breaks);
			 
			 
			 // Evaluate alpha-vector
			 this.alphaSum = 0;
			 for (int i = 0; i < this.ndim; i ++) {
				 double weightDividedByDim = weights[i] / this.dims[i];
				 //this.alpha[i] = this.emax[i] / (1 + weightDividedByDim);
				 
				 
				 weightDividedByDim = logit(weightDividedByDim);
				 double y = this.ehalf[i]*weightDividedByDim + this.emax[i];
				 this.alpha[i] = ilogit(y);
				 
				 this.alphaSum += this.alpha[i];
			 }

			 // Normalise and take mean distance
			 this.squaredDistance = 0;
			 for (int i = 0; i < this.ndim; i ++) {
				 this.alpha[i] = this.tau * this.alpha[i] / this.alphaSum;
			 }
			 return this.alpha;
		 }
		 
		 
		 public int getDimension() {
			 return this.ndim;
		 }
		 
		 @Override
		 public double value(double[] breaks) {
			 
			// double addon = 0;
			 
			 double[] weights = repairSticks(breaks);
			 
			 // Evaluate alpha-vector
			 this.alphaSum = 0;
			 for (int i = 0; i < this.ndim; i ++) {
				 double weightDividedByDim = weights[i] / this.dims[i];
				 //this.alpha[i] = this.emax[i] / (1 + this.ehalf[i]/weightDividedByDim) + addon;
				 
				 weightDividedByDim = logit(weightDividedByDim);
				 double y = this.ehalf[i]*weightDividedByDim + this.emax[i];
				 this.alpha[i] = ilogit(y);
				 
				//x = Math.log(x / (1-x));
				//y = m*x + this.getIntercept(targetNum);
				//y = 1 / (1 + Math.exp(-y)); 
				 
				 
				 this.alphaSum += this.alpha[i];
			 }

			 
			 // Normalise and take mean distance
			 this.squaredDistance = 0;
			 for (int i = 0; i < this.ndim; i ++) {
				 this.alpha[i] = this.tau * this.alpha[i] / this.alphaSum;
				 
				 this.squaredDistance += Math.pow(this.alpha[i] - this.target, 2);
				 //this.squaredDistance = Math.max(this.squaredDistance, Math.pow(this.alpha[i] - this.target, 2));
			 }
			 
			 // Return squared distance
			 return this.squaredDistance;
		 }
	    
	 }




	
	
	

}
