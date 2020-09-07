package xmlsimulator;

import java.io.File;


import beast.core.BEASTObject;
import beast.core.Input;

public class WeightedFile extends BEASTObject {

	
	final public Input<File> fileInput = new Input<>("file", "The location of the file (can be zipped)", Input.Validate.REQUIRED);
	final public Input<Double> weightInput = new Input<>("weight", "The prior weight of this file being sampled (default 1)", 1.0);
	
	
	File file;
	double weight;
	
	
	@Override
	public void initAndValidate() {
		this.file = fileInput.get();
		this.weight = weightInput.get();
		
		if (!file.exists()) throw new IllegalArgumentException("Cannot locate file " + file.getAbsolutePath());
		if (this.weight < 0) throw new IllegalArgumentException("Please set weight to at least 0");
		
	}
	
	
	public double getWeight() {
		return this.weight;
	}
	
	public double setWeight(double weight) {
		return weight;
	}
	
	
	public File getFile() {
		return this.file;
	}

	
}
