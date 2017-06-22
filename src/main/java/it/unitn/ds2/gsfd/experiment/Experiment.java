package it.unitn.ds2.gsfd.experiment;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Class that represent a single experiment.
 * Contains the configuration, results and report for the experiment.
 */
public final class Experiment {

	// generate a new random experiment
	public static Experiment generate(Set<String> nodes, int duration, int seed) {
		final Random random = new Random(seed);

		// number of nodes
		final int numberOfNodes = nodes.size();

		// generate a permutation of nodes
		// the first n will be selected for the crashes
		final List<String> permutation = new ArrayList<>(nodes);
		Collections.sort(permutation);
		Collections.shuffle(permutation, random);

		// pick a random number of nodes to crash [0, n)
		// NB: we crash at most all except one node
		final int crashes = random.nextInt(numberOfNodes);

		// generate the crashes
		final List<ExpectedCrash> expectedCrashes = IntStream.of(crashes)
			.boxed()
			.map(permutation::get)
			.map(node -> new ExpectedCrash(random.nextInt(duration), node))
			.collect(Collectors.toList());

		// return the experiment
		final String id = String.format("nodes-%d__duration-%d__seed-%d", numberOfNodes, duration, seed);
		return new Experiment(id, numberOfNodes, duration, expectedCrashes,
			1000, 2500, 3, 20);
		// TODO: proper input of gossipTime, failTime, multicastParam and multicastMaxWait
	}

	// unique identifier for the experiment
	private final String id;

	// number of nodes that participates to the experiment
	private final int numberOfNodes;

	// total duration (milliseconds) of the experiment
	private final int duration;

	// scheduled crashes
	private final List<ExpectedCrash> expectedCrashes;

	// reported crashed
	private final List<ReportedCrash> reportedCrashed;

	// frequency of Gossip
	private final long gossipTime;

	// time to consider a node failed
	private final long failTime;

	// parameter "a" of probability of multicast (catastrophe recovery)
	private final double multicastParam;

	// maximum number of times a multicast can be postponed
	private final int multicastMaxWait;

	// start time of the experiment
	private Long start;

	// status of the experiment
	private Long stop;

	// initialize a new experiment
	public Experiment(String id, int numberOfNodes, int duration, List<ExpectedCrash> expectedCrashes,
					  long gossipTime, long failTime, double multicastParam, int multicastMaxWait) {
		this.id = id;
		this.numberOfNodes = numberOfNodes;
		this.duration = duration;
		this.expectedCrashes = expectedCrashes;
		this.reportedCrashed = new LinkedList<>();
		this.gossipTime = gossipTime;
		this.failTime = failTime;
		this.multicastParam = multicastParam;
		this.multicastMaxWait = multicastMaxWait;
		this.start = null;
		this.stop = null;
	}

	public int getDuration() {
		return duration;
	}

	public List<ExpectedCrash> getExpectedCrashes() {
		return expectedCrashes;
	}

	public long getGossipTime() {
		return gossipTime;
	}

	public long getFailTime() {
		return failTime;
	}

	public double getMulticastParam() {
		return multicastParam;
	}

	public int getMulticastMaxWait() { return multicastMaxWait; }

	public void start() {
		if (start != null) {
			throw new IllegalStateException("Please call the start() method only once per experiment.");
		}
		start = System.currentTimeMillis();
	}

	public void stop() {
		if (stop != null) {
			throw new IllegalStateException("Please call the stop() method only once per experiment.");
		}
		stop = System.currentTimeMillis();
	}

	// report a new crash
	public void addCrash(String node, String reporter) {
		if (start == null) {
			throw new IllegalStateException("Please call the start() method to start the experiment first.");
		}
		final long delta = System.currentTimeMillis() - start;
		reportedCrashed.add(new ReportedCrash(delta, node, reporter));
	}

	public void generateReport() {
		if(stop == null) {
			throw new IllegalStateException("Please call the stop() method to stop the experiment first.");
		}
		// TODO: write the experiment configuration and report to the disk
	}
}
