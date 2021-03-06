package poetry;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;


import beast.core.BEASTObject;
import beast.core.Input;
import beast.core.Logger;
import beast.core.Runnable;
import beast.core.StateNode;
import beast.core.util.Log;
import beast.evolution.alignment.Alignment;
import beast.evolution.tree.TreeInterface;
import poetry.functions.XMLFunction;
import poetry.functions.XMLInputSetter;
import poetry.learning.DimensionalSampler;
import poetry.learning.DirichletSampler;
import poetry.learning.GaussianProcessSampler;
import poetry.operators.PoetryScheduler;
import poetry.sampler.DatasetSampler;
import poetry.sampler.ModelSampler;
import poetry.sampler.POEM;
import poetry.sampler.RunnableSampler;
import poetry.sampler.XMLSampler;
import poetry.util.BEAST2Weka;
import poetry.util.Lock;
import poetry.util.RuntimeLoggable;
import poetry.util.XMLUtils;




public class SimulateXML extends Runnable {

	
	enum WeightSamplers {
		Dirichlet, Dimensional, Gaussian
	}
	
	
	private static final String DATABASE_FILENAME = "database.tsv";
	private static final String RUNTIME_LOGNAME = "runtime.log";

	

	final public Input<Runnable> runnableInput = new Input<>("runner", "A runnable object (eg. mcmc)", Input.Validate.REQUIRED);
	
	final public Input<List<ModelSampler>> modelInput = new Input<>("model", "A component of the model. All of its data will be dumped into this xml.", new ArrayList<>());
	final public Input<Alignment> dataInput = new Input<>("data", "A dataset samplers for loading and sampling data. Optional", Input.Validate.REQUIRED);
	final public Input<TreeInterface> treeInput = new Input<>("tree", "The tree", Input.Validate.REQUIRED);
	final public Input<Integer> nsamplesInput = new Input<>("nsamples", "Number of xml files to produce (default 1)", 1);
	final public Input<Integer> nreplicatesInput = new Input<>("nreplicates", "Number of replicates per xml file (default 1)", 1);
	
	final public Input<List<XMLFunction>> functionsInput = new Input<>("function", "Functions which can be called during xml simulation", new ArrayList<>());
	final public Input<File> outFolderInput = new Input<>("out", "A folder to save the results into", Input.Validate.REQUIRED);
	final public Input<List<POEM>> poemsInput = new Input<>("poem", "A map between operators and log outputs", new ArrayList<>());
	final public Input<Boolean> coordinateWeightsInput = new Input<>("coordinateWeights", "Whether to coordinate weights with replicate 1 (default true)", true);
	
	final public Input<WeightSamplers> priorWeightSamplerInput = new Input<>("weightSampler", "The weight sampling method", WeightSamplers.Dirichlet, WeightSamplers.values());
	
	
	final public Input<StateNode> placeholderInput = new Input<>("placeholder", "A temporary state node which will be removed from all operators when MCMC "
			+ "begins. This enables operators to have no state nodes without MCMC throwing an error.");
	
	final public Input<Integer> updateEveryInput = new Input<>("updateEvery", "How often to update the database with weights/ESSes "
			+ "(default: only at the end of the chain)", 0);
	
	
	final public Input<List<BEASTObject>> nodesInput = new Input<>("object", "Any beast object to be added into the main file "
			+ "(eg. StateNode, CalculationNode, Distribution, Operator)", new ArrayList<>());
	
	/*
	final public Input<List<Distribution>> posteriorInput = new Input<>("distribution", "probability distribution to sample over", new ArrayList<>() );
	final public Input<List<Operator>> operatorsInput = new Input<>("operator", "operator for generating proposals in MCMC state space", new ArrayList<>());
	final public Input<List<StateNode>> stateInput = new Input<>("state", "a state node", new ArrayList<>());
	final public Input<List<StateNodeInitialiser>> initialisersInput = new Input<>("init", "a state node initiliser for determining the start state", new ArrayList<>());
	*/
	
	
	int nsamples;
	int nreplicates;
	Runnable runner;
	Alignment data;
	List<ModelSampler> modelElements;
	List<XMLFunction> functions;
	File outFolder;
	File dbFile;
	List<POEM> poems;
	int updateEvery;
	
	boolean resuming;
	int startFrom; // Start from 1 unless resuming
	
	//Document poetry;
	
	
	
	@Override
	public void initAndValidate() {
		
		this.runner = runnableInput.get();
		this.nsamples = nsamplesInput.get();
		this.nreplicates = nreplicatesInput.get();
		this.data = dataInput.get();
		this.modelElements = modelInput.get();
		this.functions = functionsInput.get();
		this.outFolder = outFolderInput.get();
		this.poems = poemsInput.get();
		this.updateEvery = updateEveryInput.get();
		this.startFrom = 1;
		this.resuming = false;
		//this.poetry = null;
		
		// Ensure that runner already has an ID
		if (this.runner.getID() == null || this.runner.getID().isEmpty()) {
			throw new IllegalArgumentException("Please provide an id for the <runner /> element");
		}
		
		
		// Positive number of replicates
		if (this.nreplicates <= 0) {
			throw new IllegalArgumentException("Please ensure that nreplicates > 0");
		}
		
		
		this.dbFile = Paths.get(this.outFolder.getPath(), DATABASE_FILENAME).toFile();
		
		
		// Create / overwrite / resume
		if (this.outFolder.exists()) {
			
			// Is it a directory
			if (!this.outFolder.isDirectory()) {
				throw new IllegalArgumentException(this.outFolder.getPath() + " is not a directory. Please provide a directory");
			}
			
			// Resume?
			if (Logger.FILE_MODE == Logger.LogFileMode.resume) {
				
				this.resuming = true;
				
				// Check the database folder exists
				if (!this.dbFile.exists() || !this.dbFile.canWrite()) {
					throw new IllegalArgumentException("Cannot resume because the database " + this.dbFile.getPath() + " does not exist. Perhaps remove the -resume flag");
				}
				
				// What is the latest row in the database?
				try {
					HashMap<String, String[]> db = PoetryAnalyser.openDatabase(this.dbFile);
					String[] indices = db.get(POEM.getXMLColumn());
					if (indices.length > 0) {
						this.startFrom = Integer.parseInt(indices[0]);
						for (int i = 0; i < indices.length; i ++) {
							int index = Integer.parseInt(indices[i]);
							this.startFrom = Math.max(index, this.startFrom);
						}
						this.startFrom ++;
						Log.warning("Resuming from sample " + this.startFrom);
					}
				} catch (Exception e) {
					e.printStackTrace();
					throw new IllegalArgumentException("Error opening the database while resuming. If you are okay with overwriting, remove the -resume flag and add -overwrite");
					
				}
				
				
			}
			
			// Overwrite?
			else if (Logger.FILE_MODE != Logger.LogFileMode.overwrite) {
				throw new IllegalArgumentException("Cannot write to " + this.outFolder.getPath() + " because it already exists. Perhaps use the -overwrite flag");
			}
			
			
		}
		
		// Make the folder
		else {
			
			// Cannot resume if it does not already exist
			if (Logger.FILE_MODE == Logger.LogFileMode.resume) {
				throw new IllegalArgumentException("Cannot resume because " + this.outFolder.getPath() + " does not exist. Perhaps remove the -resume flag");
			}
			
			// Try to create the out folder
			if (!this.outFolder.mkdir()) {
				throw new IllegalArgumentException("Failed to create directory at " + this.outFolder.getPath());
			}
		}

		
		// Prepare database file
		if (!this.resuming) {
			try {
				this.initDatabase();
			} catch (FileNotFoundException e) {
				throw new IllegalArgumentException("Failed to initialise database at " + this.outFolder.getPath());
			}
		}

	}
	
	
	@Override
	public void run() throws Exception {

		
		int ndigits = 2 + (int) Math.floor(1 + Math.log(this.nsamples) / Math.log(10));
		for (int sample = this.startFrom; sample <= this.nsamples; sample ++) {
			
			// Pad the string so that the files are name xml0001, xml0002, ..., xml0099. 
			// This looks nicer and ensures the alphabetical order is the same as the numeric order
			String sampleStr = padLeftZeros("" + sample, ndigits);
		
			Log.warning("--------------------------------------------------");
			Log.warning("Sample " + sampleStr);
			Log.warning("--------------------------------------------------\n");
			
			// Sample alignments
			if (this.data != null && this.data instanceof XMLSampler) {
				DatasetSampler d = (DatasetSampler) this.data;
				d.reset();
			}
			
			// Sample model components
			for (ModelSampler model : this.modelElements) {
				model.reset();
			}
			

			
			// Sample a search algorithm
			if (this.runner instanceof RunnableSampler) ((RunnableSampler)this.runner).reset();
			
			
			// Print the new xml
			String sXML = this.toXML(sample);
			this.writeXMLFile(sXML, sampleStr);
			
			
			// Update database
			this.appendToDatabase(sample);
			
		
		}
		
		Log.warning("Done!");
		
	}
	
	
	
	
	/**
	 * Write the xml file in a folder indexed by its sample number
	 * @param xml
	 * @param sampleStr
	 * @return
	 * @throws Exception 
	 */
	protected void writeXMLFile(String xml, String sampleStr) throws Exception {
		
		// Path to subfolder. Build it if it does not exist
		File folder = Paths.get(this.outFolder.getPath(), "xml" + sampleStr).toFile();
		if (!folder.exists()) {
			if (!folder.mkdir()) throw new IllegalArgumentException("Cannot generate folder " + folder.getPath());
		}
		

		
		// Path to xml file
		Path path = Paths.get(folder.getPath(), "out.xml");
		
		// Write the file
		PrintStream out = new PrintStream(path.toFile());
		out.println(xml);
		out.close();
		
		
		// Path to replicate subsubfolders
		for (int rep = 1; rep <= this.nreplicates; rep++) {
			File replicateFolder = Paths.get(folder.getPath(), "replicate" + rep).toFile();
			if (!replicateFolder.exists()) {
				if (!replicateFolder.mkdir()) throw new IllegalArgumentException("Cannot generate folder " + replicateFolder.getPath());
			}
		}
		
		
		
		// Write the poem file
		//Path poemPath = Paths.get(folder.getPath(), "poems.xml");
		//out = new PrintStream(poemPath.toFile());
		//out.println(XMLUtils.getXMLStringFromDocument(this.poetry));
		//out.close();
		
	}
	
	
	/**
	 * Get the output xml string 
	 * All elements will be placed inside the runnable
	 * @return
	 * @throws Exception 
	 */
	protected String toXML(int sampleNum) throws Exception {
		
		
		XMLSimProducer producer = new XMLSimProducer();
		String sXML = producer.toXML(this);
		

		
		// xml comments
		String comments = "\nXML sample " + sampleNum + "\n";
		
		
		// Parse the xml and get the runner element
        Document doc = XMLUtils.loadXMLFromString(sXML);
        Element runner = XMLUtils.getElementById(doc, this.runner.getID());
        Element run = XMLUtils.getElementById(doc, this.getID());
        

		// Move the alignment to the head if it is not already
        if (this.data != null) {
        	Element dataset = XMLUtils.getElementById(doc, this.data.getID());
			Element datasetParent = (Element) dataset.getParentNode();
			datasetParent.removeChild(dataset);
			run.getParentNode().insertBefore(dataset, run);
			if (!datasetParent.getNodeName().equals("beast")) {
				datasetParent.setAttribute(dataset.getNodeName(), "@" + this.data.getID());
			}
        }

        
        // Search mode
        if (this.runner instanceof RunnableSampler) {
        	RunnableSampler sampler = (RunnableSampler) this.runner;
        	sampler.tidyXML(doc, runner, this.functions);
        	comments += sampler.getComments() + "\n";
        	runner = XMLUtils.getElementById(doc, this.runner.getID());
        }
        



        // Tidy the XML of all XMLSampler models (and get some comments)
		for (ModelSampler model : this.modelElements) {
			model.tidyXML(doc, run, this.functions);
			comments += model.getComments() + "\n";
		}
		
		
		// Call any input setter functions
		for (XMLFunction function : this.functions) {
			if (function instanceof XMLInputSetter) {
				((XMLInputSetter)function).tidyXML(doc);
			}
		}
		
		

		 // Tidy the XML of all XMLSampler datasets (and get some comments)
        if (this.data != null && this.data instanceof XMLSampler) {
			DatasetSampler d = (DatasetSampler) this.data;
			d.tidyXML(doc, run, this.functions);
			comments += d.getComments() + "\n";
		}
        

 
        
        // Operator weights
        for (POEM poem : this.poems) {
        	poem.tidyXML(doc, runner, this.functions);
        	comments += poem.getComments() + "\n";
        }
      
		
    	// Generate poem operator schedule
		this.writePoems(sampleNum, doc, runner, run);
		
        
        // Replace this runnable element (and all of its children) with its runnable child
        Element parent = (Element) run.getParentNode();
        parent.removeChild(run);
        parent.appendChild(runner);
  
		
		// Add a comment describing the dataset/model etc
        Node element = doc.getFirstChild();
		Comment comment = doc.createComment(comments);
		element.getParentNode().insertBefore(comment, element);
		
		
	
		
		
		// Merge elements which share an id
		XMLUtils.mergeElementsWhichShareID(doc);
		


		
		// Create a temporal logger
		Element runtimeLogger = doc.createElement("logger");
		runner.appendChild(runtimeLogger);
		runtimeLogger.setAttribute("id", XMLUtils.getUniqueID(doc, "RuntimeLogger"));
		runtimeLogger.setAttribute("fileName", RUNTIME_LOGNAME);
		runtimeLogger.setAttribute("logEvery", "" + 10000);
		runtimeLogger.setAttribute("spec", Logger.class.getCanonicalName());
		Element runtimeLoggable = doc.createElement("log");
		runtimeLogger.appendChild(runtimeLoggable);
		runtimeLoggable.setAttribute("spec", RuntimeLoggable.class.getCanonicalName());
		runtimeLoggable.setAttribute("static", "true");
		runtimeLoggable.setAttribute("id", XMLUtils.getUniqueID(doc, "runtime"));
		
		
		
		// Sort elements by putting operators and loggers at the bottom of runnable
		List<Element> operators = XMLUtils.getElementsByName(runner, "operator");
		List<Element> loggers = XMLUtils.getElementsByName(runner, "logger");
		for (Element operator : operators) {
			runner.removeChild(operator);
			runner.appendChild(operator);
		}
		for (Element logger : loggers) {
			runner.removeChild(logger);
			runner.appendChild(logger);
		}
		
		
		
		
		// Final check: ensure there are no operators which are not affiliated with poems
		for (Element operator : operators) {
			
			int numPoems = 0;
			for (POEM poem : this.poems) {
				if (poem.getOperatorID().equals(operator.getAttribute("id"))) {
					numPoems ++;
				}
			}
			
			if (numPoems != 1) throw new Exception("Operator " + operator.getAttribute("id") + " must have exactly 1 POEMS but it has " + numPoems);
		}
		
		
		
        sXML = XMLUtils.getXMLStringFromDocument(doc);
		return sXML;
		
	}
	
	
	/**
	 * Write xml for a poetry scheduler
	 * @param doc
	 */
	private void writePoems(int sampleNum, Document doc, Element runner, Element run) throws Exception {
		
		// Create new document
		//DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
	   //DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
	    //this.poetry = dBuilder.newDocument();
	   // Element beast = (Element) this.poetry.importNode(XMLUtils.getElementsByName(doc, "beast").get(0), false);
	    
		
		
		
		// How often to update the database?
		String updateEvery2 = "" + this.updateEvery;
		if (this.updateEvery <= 0) {
			if (runner.hasAttribute("chainLength")) updateEvery2 = runner.getAttribute("chainLength");
			else updateEvery2 = "1000000";
		}
		
		
		
	    // Create the PoetryAnalyser runnable element and populate its inputs
		String relativeDir = "../../";
	    Element scheduler = doc.createElement("operatorschedule");
	    scheduler.setAttribute("spec", PoetryScheduler.class.getCanonicalName());
	    scheduler.setAttribute("database", relativeDir + DATABASE_FILENAME);
	    scheduler.setAttribute("runtime", RUNTIME_LOGNAME);
	    scheduler.setAttribute("number", "" + sampleNum);
	    scheduler.setAttribute("updateEvery", updateEvery2);
	    scheduler.setAttribute("coordinateWeights", "" + coordinateWeightsInput.get());
	    if (placeholderInput.get() != null) scheduler.setAttribute("placeholder", "@" + placeholderInput.get().getID());
	    runner.appendChild(scheduler);
	    
	    
	    // Use a dirichlet/dimensional sampler initially
	    Element sampler = doc.createElement("sampler");
	    switch (priorWeightSamplerInput.get()) {
	    
		    case Dirichlet:{
		    	sampler.setAttribute("spec", DirichletSampler.class.getCanonicalName());
		    	break;
		    }
		    
		    
		    case Dimensional:{
		    	sampler.setAttribute("spec", DimensionalSampler.class.getCanonicalName());
		    	break;
		    }
		    
		    
		    case Gaussian:{
		    	sampler.setAttribute("spec", GaussianProcessSampler.class.getCanonicalName());
		    	sampler.setAttribute("poetry", "$(filebase).poetry");
		    	sampler.setAttribute("acquisition", "EI");
		    	sampler.setAttribute("explorativity", "10");
		    	sampler.setAttribute("decay", "0.7");
		    	sampler.setAttribute("minExplorativity", "-10");
		    	sampler.setAttribute("priorWeight", "5");
		    	sampler.setAttribute("tree", "@" + treeInput.get().getID());
		    	sampler.setAttribute("data", "@" + dataInput.get().getID());
		    	sampler.setAttribute("dataset", "~/nesi/nobackup/nesi00390/jordan/weka.sample.arff"); // tmp directory
		    	break;
		    }
		    
		    default:{
		    	throw new IllegalArgumentException("Cannot handle " + priorWeightSamplerInput.get() + " samplers");
		    }
	    
	    }
	    sampler.setAttribute("static", "true");
	    scheduler.appendChild(sampler);
	    
		
	    // Copy poems over from main doc
	    for (Element poem : XMLUtils.getElementsByName(run, "poem")) {
	    	
	    	// Only consider poems which have non-zero weight
	    	POEM poemObj = null;
	    	for (POEM p : this.poems) {
	    		if (p.getID().equals(poem.getAttribute("id"))){
	    			poemObj = p;
	    			break;
	    		}
	    	}
	    	if (!poemObj.isApplicableToModel()) {
	    		continue;
	    	}
	    	
	    	Element imported =  (Element) doc.importNode(poem, true);
	    	for (Node child : XMLUtils.nodeListToList(imported.getChildNodes())) {
	    		if (!(child instanceof Element)) continue;
	    		Element childEle = (Element)child;
	    		if (childEle.getNodeName().equals("log")) {
	    			imported.removeChild(childEle);
	    		}

	    	}
	    	scheduler.appendChild(imported);
	    }
		
	}


	/**
	 * Prepare header for the database
	 * @throws FileNotFoundException 
	 */
	protected void initDatabase() throws FileNotFoundException {

		
		
		PrintStream dbOut = new PrintStream(this.dbFile);
		
		// Data summary
		dbOut.print(POEM.getXMLColumn() + "\t");
		dbOut.print(POEM.getReplicateColumn() + "\t");
		dbOut.print(POEM.getStartedColumn() + "\t");
		dbOut.print("dataset\t");
		dbOut.print(BEAST2Weka.getNtaxaAttr().name() + "\t");
		dbOut.print(BEAST2Weka.getNsitesAttr().name() + "\t");
		dbOut.print(BEAST2Weka.getNpatternsAttr().name() + "\t");
		dbOut.print(BEAST2Weka.getNpartitionsAttr().name() + "\t");
		dbOut.print(BEAST2Weka.getNcalibrationsAttr().name() + "\t");
		dbOut.print("nspecies\t");
		dbOut.print(BEAST2Weka.getPgapsAttr().name() + "\t");
		dbOut.print(BEAST2Weka.getNcharAttr().name() + "\t");
		dbOut.print("dated\t");
		dbOut.print(BEAST2Weka.getTreeHeightAttr().name() + "\t");
		
		
		
		
		// Model summary
		for (ModelSampler model : this.modelElements) {
			dbOut.print(model.getID() + "\t");
		}
		
		
		// Operator weight summary
		dbOut.print("search.mode\t");
		for (POEM poem : this.poems) {
			dbOut.print(poem.getDimColName() + "\t");
			dbOut.print(poem.getWeightColname() + "\t");
			dbOut.print(poem.getESSColname() + "\t");
		}
		
		
		// ESS summary
		dbOut.print(POEM.getMeanColumnName() + "\t");
		dbOut.print(POEM.getStddevColumnName() + "\t");
		dbOut.print(POEM.getCoefficientOfVariationColumnName() + "\t");
		dbOut.print(POEM.getNumberOfStatesColumnName() + "\t");
		
		
		// Runtime (million states per hr)
		dbOut.print(POEM.getRuntimeSmoothColumn() + "\t");
		dbOut.print(POEM.getRuntimeRawColumn());
		dbOut.println();
		
		dbOut.close();
	
		
	}
	
	
	/**
	 * Pad string left with zeros
	 * @param inputString
	 * @param length
	 * @return
	 */
	public static String padLeftZeros(String inputString, int length) {
	    if (inputString.length() >= length) {
	        return inputString;
	    }
	    StringBuilder sb = new StringBuilder();
	    while (sb.length() < length - inputString.length()) {
	        sb.append('0');
	    }
	    sb.append(inputString);
	 
	    return sb.toString();
	}
	
	

	/**
	 * Prepare header for the database
	 * @throws FileNotFoundException 
	 */
	protected void appendToDatabase(int sampleNum) throws FileNotFoundException {
		
		// Append
		PrintStream dbOut = new PrintStream(new BufferedOutputStream(new FileOutputStream(this.dbFile, true)));
	
		String dataset = this.data == null ? "NA" : !(this.data instanceof DatasetSampler) ? "NA" : ((DatasetSampler)this.data).getFilePath();
		int npartitions = this.data == null ? 0 : !(this.data instanceof DatasetSampler) ? 1 : ((DatasetSampler)this.data).getNumPartitions();
		double pgaps = this.data == null ? 0 : !(this.data instanceof DatasetSampler) ? 0 : ((DatasetSampler)this.data).getProportionGaps();
		String datedTips = this.data == null ? "false" : !(this.data instanceof DatasetSampler) ? "NA" : "" + ((DatasetSampler)this.data).tipsAreDated();
		String treeHeight = this.data == null ? "0" : !(this.data instanceof DatasetSampler) ? "NA" : "" + ((DatasetSampler)this.data).getEstimatedTreeHeight();
		int nspecies = this.data == null ? 1 :  !(this.data instanceof DatasetSampler) ? 0 : ((DatasetSampler)this.data).getNumSpecies();
		int ncalibrations = this.data == null ? 0 : !(this.data instanceof DatasetSampler) ? 0 : ((DatasetSampler)this.data).getNumCalibrations();
		
		
		System.out.println("ESTIMATED HEIGHT " + treeHeight);
		
		for (int rep = 1; rep <= this.nreplicates; rep++) {
		
			
			// Dataset summary
			dbOut.print(sampleNum + "\t"); // Sample number
			dbOut.print(rep + "\t"); // Replicate number
			dbOut.print("false\t"); // Has the sample started running?
			dbOut.print(dataset + "\t"); // Dataset folder 
			dbOut.print((this.data == null ? 0 : this.data.getTaxonCount()) + "\t"); // Taxon count
			dbOut.print((this.data == null ? 0 : this.data.getSiteCount()) + "\t"); // Site count
			dbOut.print((this.data == null ? 0 : this.data.getPatternCount()) + "\t"); // Pattern count
			dbOut.print(npartitions + "\t"); // Number of partitions
			dbOut.print(ncalibrations + "\t"); // Number of calibrations 
			dbOut.print(nspecies + "\t");
			dbOut.print(pgaps + "\t"); // Proportion of sites which are gaps
			dbOut.print((this.data == null ? 0 : this.data.getDataType().getStateCount()) + "\t"); // Number of characters (4 for nt, 20 for aa etc)
			dbOut.print(datedTips + "\t"); // Are the tips dated?
			dbOut.print(treeHeight + "\t"); // Estimate the tree height using neighbour joining
			
			
			// Model summary
			for (ModelSampler model : this.modelElements) {
				dbOut.print(model.getSampledID() + "\t");
			}
			
			// Inference engine
			if (this.runner instanceof RunnableSampler) {
				String id = ((RunnableSampler)this.runner).getSampledID();
				dbOut.print(id + "\t");
			}else {
				dbOut.print("NA\t");
			}
			
			
			// POEM weights, dimension, ESS
			for (POEM poem : this.poems) {
				dbOut.print("NA\tNA\tNA\t");
			}
			
			
			// ESS summary
			dbOut.print("NA\t");
			dbOut.print("NA\t");
			dbOut.print("NA\t");
			dbOut.print("NA\t");
			
			
			
			// Runtime (num hr)
			dbOut.print("NA\t");
			dbOut.print("NA");
	
			dbOut.println();
			
		}
		
		dbOut.close();
		
		
	}
	
	
	

}








