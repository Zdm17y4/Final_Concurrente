import java.io.*;
import java.util.*;

/**
 * Training Module - Entry point for neural network training
 * 
 * Usage:
 *   java TrainingModule train <inputs_file> <outputs_file> [epochs]
 *   java TrainingModule predict <model_file> <input_values...>
 *   java TrainingModule demo
 * 
 * File format for inputs/outputs: CSV with one sample per line
 */
public class TrainingModule {
    
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }
        
        String command = args[0].toLowerCase();
        
        try {
            switch (command) {
                case "train":
                    handleTrain(args);
                    break;
                case "predict":
                    handlePredict(args);
                    break;
                case "demo":
                    runXorDemo();
                    break;
                default:
                    printUsage();
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void printUsage() {
        System.out.println("TrainingModule - Neural Network Training System");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  train <inputs.csv> <outputs.csv> [epochs] [model_output_path]");
        System.out.println("      Train a new model with the given data");
        System.out.println();
        System.out.println("  predict <model.bin> <value1,value2,...>");
        System.out.println("      Load a model and make a prediction");
        System.out.println();
        System.out.println("  demo");
        System.out.println("      Run XOR demonstration (no files needed)");
    }
    
    /**
     * Handle training command
     */
    private static void handleTrain(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: train <inputs.csv> <outputs.csv> [epochs] [model_output_path]");
            return;
        }
        
        String inputsFile = args[1];
        String outputsFile = args[2];
        int epochs = args.length > 3 ? Integer.parseInt(args[3]) : 1000;
        
        // Load data
        double[][] inputs = loadCsv(inputsFile);
        double[][] outputs = loadCsv(outputsFile);
        
        if (inputs.length != outputs.length) {
            throw new IllegalArgumentException("Inputs and outputs must have same number of samples");
        }
        
        System.out.println("Loaded " + inputs.length + " samples");
        System.out.println("Input size: " + inputs[0].length);
        System.out.println("Output size: " + outputs[0].length);
        
        // Determine hidden layer size (heuristic: average of input and output)
        int hiddenSize = Math.max(4, (inputs[0].length + outputs[0].length) / 2);
        
        // Create and train network
        NeuralNetwork nn = new NeuralNetwork(inputs[0].length, hiddenSize, outputs[0].length);
        nn.train(inputs, outputs, epochs);
        
        // Save model
        String modelPath;
        if (args.length > 4) {
            modelPath = args[4];
        } else {
            modelPath = "model_" + nn.getModelId() + ".bin";
        }
        nn.save(modelPath);
        
        // Output model ID (for integration with distributed system)
        System.out.println("MODEL_ID:" + nn.getModelId());
        System.out.println("MODEL_PATH:" + modelPath);
    }
    
    /**
     * Handle prediction command
     */
    private static void handlePredict(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: predict <model.bin> <value1,value2,...>");
            return;
        }
        
        String modelPath = args[1];
        String inputStr = args[2];
        
        // Load model
        NeuralNetwork nn = NeuralNetwork.load(modelPath);
        System.out.println("Loaded model: " + nn);
        
        // Parse input
        String[] parts = inputStr.split(",");
        double[] input = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            input[i] = Double.parseDouble(parts[i].trim());
        }
        
        // Make prediction
        double[] output = nn.predict(input);
        
        // Print result
        System.out.print("PREDICTION:");
        for (int i = 0; i < output.length; i++) {
            System.out.print((i > 0 ? "," : "") + String.format("%.6f", output[i]));
        }
        System.out.println();
    }
    
    /**
     * XOR demonstration - proves the network can learn non-linear patterns
     */
    private static void runXorDemo() {
        System.out.println("=== XOR Demo ===");
        System.out.println("Training a neural network to learn the XOR function");
        System.out.println();
        
        // XOR training data
        double[][] inputs = {
            {0, 0},
            {0, 1},
            {1, 0},
            {1, 1}
        };
        
        double[][] outputs = {
            {0},
            {1},
            {1},
            {0}
        };
        
        // Create network: 2 inputs, 4 hidden, 1 output
        NeuralNetwork nn = new NeuralNetwork(2, 4, 1);
        
        // Train
        nn.train(inputs, outputs, 5000);
        
        // Test
        System.out.println();
        System.out.println("Testing predictions:");
        for (int i = 0; i < inputs.length; i++) {
            double[] prediction = nn.predict(inputs[i]);
            System.out.printf("Input: [%.0f, %.0f] -> Expected: %.0f, Predicted: %.4f%n",
                inputs[i][0], inputs[i][1], outputs[i][0], prediction[0]);
        }
        
        // Save demo model
        try {
            String modelPath = "model_xor_demo.bin";
            nn.save(modelPath);
            System.out.println();
            System.out.println("Demo model saved: " + modelPath);
            System.out.println("MODEL_ID:" + nn.getModelId());
        } catch (IOException e) {
            System.err.println("Failed to save demo model: " + e.getMessage());
        }
    }
    
    /**
     * Load CSV file into 2D array
     */
    private static double[][] loadCsv(String path) throws IOException {
        List<double[]> rows = new ArrayList<>();
        
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                
                String[] parts = line.split(",");
                double[] row = new double[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    row[i] = Double.parseDouble(parts[i].trim());
                }
                rows.add(row);
            }
        }
        
        return rows.toArray(new double[0][]);
    }
}
