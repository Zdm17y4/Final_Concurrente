import java.io.*;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Simple Multilayer Perceptron (MLP) Neural Network
 * - One hidden layer
 * - Sigmoid activation
 * - Backpropagation training
 * - Parallelized batch training using ExecutorService
 */
public class NeuralNetwork implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String modelId;
    private final int inputSize;
    private final int hiddenSize;
    private final int outputSize;
    
    private double[][] weightsInputHidden;  // [inputSize][hiddenSize]
    private double[][] weightsHiddenOutput; // [hiddenSize][outputSize]
    private double[] biasHidden;
    private double[] biasOutput;
    
    private double learningRate = 0.5;
    
    public NeuralNetwork(int inputSize, int hiddenSize, int outputSize) {
        this.modelId = UUID.randomUUID().toString();
        this.inputSize = inputSize;
        this.hiddenSize = hiddenSize;
        this.outputSize = outputSize;
        
        initializeWeights();
    }
    
    private void initializeWeights() {
        Random rand = new Random();
        
        weightsInputHidden = new double[inputSize][hiddenSize];
        weightsHiddenOutput = new double[hiddenSize][outputSize];
        biasHidden = new double[hiddenSize];
        biasOutput = new double[outputSize];
        
        // Xavier initialization
        double limitIH = Math.sqrt(6.0 / (inputSize + hiddenSize));
        double limitHO = Math.sqrt(6.0 / (hiddenSize + outputSize));
        
        for (int i = 0; i < inputSize; i++) {
            for (int j = 0; j < hiddenSize; j++) {
                weightsInputHidden[i][j] = (rand.nextDouble() * 2 - 1) * limitIH;
            }
        }
        
        for (int i = 0; i < hiddenSize; i++) {
            for (int j = 0; j < outputSize; j++) {
                weightsHiddenOutput[i][j] = (rand.nextDouble() * 2 - 1) * limitHO;
            }
            biasHidden[i] = 0;
        }
        
        for (int i = 0; i < outputSize; i++) {
            biasOutput[i] = 0;
        }
    }
    
    // Activation function: Sigmoid
    private double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }
    
    // Derivative of sigmoid
    private double sigmoidDerivative(double x) {
        return x * (1.0 - x);
    }
    
    /**
     * Forward propagation
     */
    public double[] predict(double[] input) {
        if (input.length != inputSize) {
            throw new IllegalArgumentException("Input size mismatch: expected " + inputSize + ", got " + input.length);
        }
        
        // Hidden layer
        double[] hidden = new double[hiddenSize];
        for (int j = 0; j < hiddenSize; j++) {
            double sum = biasHidden[j];
            for (int i = 0; i < inputSize; i++) {
                sum += input[i] * weightsInputHidden[i][j];
            }
            hidden[j] = sigmoid(sum);
        }
        
        // Output layer
        double[] output = new double[outputSize];
        for (int k = 0; k < outputSize; k++) {
            double sum = biasOutput[k];
            for (int j = 0; j < hiddenSize; j++) {
                sum += hidden[j] * weightsHiddenOutput[j][k];
            }
            output[k] = sigmoid(sum);
        }
        
        return output;
    }
    
    /**
     * Train on a single sample (backpropagation)
     * Returns the error for this sample
     */
    private synchronized double trainSingle(double[] input, double[] target) {
        // Forward pass
        double[] hidden = new double[hiddenSize];
        for (int j = 0; j < hiddenSize; j++) {
            double sum = biasHidden[j];
            for (int i = 0; i < inputSize; i++) {
                sum += input[i] * weightsInputHidden[i][j];
            }
            hidden[j] = sigmoid(sum);
        }
        
        double[] output = new double[outputSize];
        for (int k = 0; k < outputSize; k++) {
            double sum = biasOutput[k];
            for (int j = 0; j < hiddenSize; j++) {
                sum += hidden[j] * weightsHiddenOutput[j][k];
            }
            output[k] = sigmoid(sum);
        }
        
        // Calculate output layer errors
        double[] outputErrors = new double[outputSize];
        double totalError = 0;
        for (int k = 0; k < outputSize; k++) {
            double error = target[k] - output[k];
            outputErrors[k] = error * sigmoidDerivative(output[k]);
            totalError += error * error;
        }
        
        // Calculate hidden layer errors
        double[] hiddenErrors = new double[hiddenSize];
        for (int j = 0; j < hiddenSize; j++) {
            double error = 0;
            for (int k = 0; k < outputSize; k++) {
                error += outputErrors[k] * weightsHiddenOutput[j][k];
            }
            hiddenErrors[j] = error * sigmoidDerivative(hidden[j]);
        }
        
        // Update weights: hidden -> output
        for (int j = 0; j < hiddenSize; j++) {
            for (int k = 0; k < outputSize; k++) {
                weightsHiddenOutput[j][k] += learningRate * outputErrors[k] * hidden[j];
            }
        }
        for (int k = 0; k < outputSize; k++) {
            biasOutput[k] += learningRate * outputErrors[k];
        }
        
        // Update weights: input -> hidden
        for (int i = 0; i < inputSize; i++) {
            for (int j = 0; j < hiddenSize; j++) {
                weightsInputHidden[i][j] += learningRate * hiddenErrors[j] * input[i];
            }
        }
        for (int j = 0; j < hiddenSize; j++) {
            biasHidden[j] += learningRate * hiddenErrors[j];
        }
        
        return totalError / outputSize;
    }
    
    /**
     * Train the network with parallelization
     * Uses all available CPU cores to process batches
     */
    public void train(double[][] inputs, double[][] outputs, int epochs) {
        int numCores = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(numCores);
        
        System.out.println("Training with " + numCores + " threads");
        System.out.println("Model ID: " + modelId);
        System.out.println("Samples: " + inputs.length + ", Epochs: " + epochs);
        
        for (int epoch = 0; epoch < epochs; epoch++) {
            final int currentEpoch = epoch;
            double totalError = 0;
            
            // Divide samples among threads
            int samplesPerThread = inputs.length / numCores;
            CountDownLatch latch = new CountDownLatch(numCores);
            double[] threadErrors = new double[numCores];
            
            for (int t = 0; t < numCores; t++) {
                final int threadId = t;
                final int start = t * samplesPerThread;
                final int end = (t == numCores - 1) ? inputs.length : (t + 1) * samplesPerThread;
                
                executor.submit(() -> {
                    double error = 0;
                    for (int i = start; i < end; i++) {
                        error += trainSingle(inputs[i], outputs[i]);
                    }
                    threadErrors[threadId] = error;
                    latch.countDown();
                });
            }
            
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            for (double err : threadErrors) {
                totalError += err;
            }
            
            if (epoch % 100 == 0 || epoch == epochs - 1) {
                System.out.printf("Epoch %d/%d - Error: %.6f%n", epoch + 1, epochs, totalError / inputs.length);
            }
        }
        
        executor.shutdown();
        System.out.println("Training complete!");
    }
    
    /**
     * Save model to file
     */
    public void save(String path) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(path))) {
            oos.writeObject(this);
        }
        System.out.println("Model saved to: " + path);
    }
    
    /**
     * Load model from file
     */
    public static NeuralNetwork load(String path) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(path))) {
            return (NeuralNetwork) ois.readObject();
        }
    }
    
    public String getModelId() {
        return modelId;
    }
    
    public int getInputSize() {
        return inputSize;
    }
    
    public int getOutputSize() {
        return outputSize;
    }
    
    @Override
    public String toString() {
        return String.format("NeuralNetwork[id=%s, architecture=%d-%d-%d]", 
            modelId, inputSize, hiddenSize, outputSize);
    }
}
