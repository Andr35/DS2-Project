package it.unitn.ds2.gsfd.actors;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import akka.event.DiagnosticLoggingAdapter;
import akka.event.Logging;
import akka.japi.Creator;
import it.unitn.ds2.gsfd.messages.*;
import it.unitn.ds2.gsfd.messages.Shutdown;
import it.unitn.ds2.gsfd.protocol.*;
import it.unitn.ds2.gsfd.utils.NodeMap;
import scala.concurrent.duration.Duration;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Node: this actor implements the gossip style failure detector system
 * described in the paper "A Gossip-Style Failure Detection Service".
 * The node simulates crashes and reports the detected one to the central tracker.
 */
public final class NodeActor extends AbstractActor implements BaseActor {

	/**
	 * Initialize a new node which will register itself to the Tracker node.
	 * Once registered, the node will wait for commands from the tracker.
	 * The tracker starts and stops experiments, schedules crashes and collect
	 * the detected failures.
	 *
	 * @param trackerAddress Akka address of the tracker to contact.
	 * @return Akka Props object.
	 */
	public static Props init(final String trackerAddress) {
		return Props.create(new Creator<NodeActor>() {
			private static final long serialVersionUID = 1L;

			@Override
			public NodeActor create() {
				return new NodeActor(trackerAddress);
			}
		});
	}

	// address of the Tracker
	private final String trackerAddress;

	// with the newest Akka version actors are state machines...
	// if ready, accepts messages other than StartExperiment and StopExperiment
	private Receive ready;
	private Receive notReady;

	// timeout to simulate crash; cancelled by StopExperiment message.
	private Cancellable selfCrashTimeout;

	// time without heartbeat update to consider a node failed
	private long failTime;

	// time to wait before removing a failed node from beats
	private long cleanupTime;

	// nodes information (includes heartbeat counter, window, and fail control);
	// extends HashMap<ActorRef, NodeInfo>
	private NodeMap nodes;

	// timeout to issue another Gossip
	private long gossipTime;
	private Cancellable gossipTimeout;

	// parameter used to decide if the node will multicast
	private double multicastParam;

	// number of times multicast was postponed
	private long multicastWait;

	// parameter used to decide the maximum number of times multicast can be postponed
	private long multicastMaxWait;

	// timeout to issue another try for multicast
	private Cancellable multicastTimeout;

	// if true, the node replies to the gossip sender
	private boolean pullByGossip;

	// log, used for debug proposes
	private final DiagnosticLoggingAdapter log;

	/**
	 * Create a new node BaseActor. The created node will
	 * register itself on the Tracker and wait for instructions.
	 *
	 * @param trackerAddress Akka address of the tracker.
	 */
	private NodeActor(String trackerAddress) {

		// initialize values
		this.trackerAddress = trackerAddress;

		// extract my identifier
		final String id = idFromRef(getSelf());

		// setup log context
		final Map<String, Object> mdc = new HashMap<String, Object>() {{
			put("actor", "Node [" + id + "]:");
		}};
		this.log = Logging.getLogger(this);
		this.log.setMDC(mdc);

		// messages to accept in the NOT_READY state (before an experiment)
		notReady = receiveBuilder()
			.match(Shutdown.class, msg -> onShutdown())
			.match(StartExperiment.class, this::onStart)
			.match(StopExperiment.class, msg -> onStop())
			.matchAny(msg -> log.warning("Dropped message -> " + msg))
			.build();

		// messages to accept in the READY state (during an experiment)
		ready = receiveBuilder()
			.match(Shutdown.class, msg -> onShutdown())
			.match(StartExperiment.class, this::onStart)
			.match(StopExperiment.class, msg -> onStop())
			.match(SelfCrash.class, msg -> onCrash())
			.match(GossipReminder.class, msg -> sendGossip())
			.match(Gossip.class, this::onGossip)
			.match(GossipReply.class, this::onGossipReply)
			.match(Fail.class, this::onFail)
			.match(Cleanup.class, this::onCleanup)
			.match(CatastropheReminder.class, msg -> sendMulticast())
			.match(CatastropheMulticast.class, this::onMulticast)
			.matchAny(msg -> log.error("Received unknown message -> " + msg))
			.build();

		// at the beginning, we must wait for the tracker -> NOT_READY
		getContext().become(notReady);
	}

	@Override
	public void preStart() throws Exception {
		super.preStart();
		log.info("StartExperiment... register on the Tracker");
		sendToTracker(new Registration());
	}

	/**
	 * For each type of message, call the relative callback
	 * to keep this method short and clean.
	 */
	@Override
	public Receive createReceive() {
		return receiveBuilder()
			.match(Shutdown.class, msg -> onShutdown())
			.match(StartExperiment.class, msg -> getContext().become(ready))
			.match(StopExperiment.class, msg -> getContext().become(notReady))
			.match(SelfCrash.class, msg -> getContext().become(notReady))
			.matchAny(msg -> log.error("Received unknown message -> " + msg))
			.build();
	}

	private void onShutdown() {
		log.warning("Tracker requested to shutdown the system... terminate");
		getContext().getSystem().terminate();
	}

	private void onStart(StartExperiment msg) {

		getContext().become(ready);

		// set the gossip strategy
		pullByGossip = msg.isPullByGossip();

		// schedule the crash if needed
		final Long delta = msg.getDelta();
		if (delta != null) {
			selfCrashTimeout = sendToSelf(new SelfCrash(), delta);
		}

		// set times for timeouts
		gossipTime = msg.getGossipTime();
		failTime = msg.getFailTime();
		cleanupTime = 2 * failTime;

		// set the structures for nodes, and start timeouts
		nodes = new NodeMap(msg.getNodes(), getSelf());
		msg.getNodes().forEach(ref -> {
			if (ref != getSelf()) {
				// schedule to self to catch failure of the node
				Cancellable failTimeout = sendToSelf(new Fail(ref, 0), failTime);
				nodes.get(ref).setTimeout(failTimeout);
			}
		});

		// schedule reminder to perform first Gossip
		gossipTimeout = sendToSelf(new GossipReminder(), gossipTime);

		// setup for catastrophe recovery
		multicastParam = msg.getMulticastParam();
		multicastMaxWait = msg.getMulticastMaxWait();
		multicastWait = 0;

		// schedule reminder to attempt multicast
		multicastTimeout = sendToSelf(new CatastropheReminder(), 1000);

		// debug
		if (delta != null) log.info("onStart complete (faulty, crashes in " + msg.getDelta() + ")");
		else log.info("onStart complete (correct)");
		log.debug("nodes: " + nodes.beatsToString());
	}

	private void onStop() {
		getContext().become(notReady);

		if (selfCrashTimeout != null) selfCrashTimeout.cancel();
		if (gossipTimeout != null) gossipTimeout.cancel();
		if (multicastTimeout != null) multicastTimeout.cancel();
		nodes.clear();
		log.info("onStop complete");
	}

	private void onCrash() {
		getContext().become(notReady);
		sendToTracker(new Crash());
		log.info("onCrash complete");
	}

	private void sendGossip() {

		// increment node's own heartbeat counter
		nodes.get(getSelf()).heartbeat();

		// TODO: check that this works with the experiments
		// pick random correct node
		ActorRef gossipNode = nodes.pickNode();
		if (gossipNode != null) {

			// gossip the beats to the random node
			gossipNode.tell(new Gossip(nodes.getBeats()), getSelf());
			log.debug("gossiped to {}: " + nodes.beatsToString(), idFromRef(gossipNode));

			// lower the probability of gossiping the same node soon
			nodes.get(gossipNode).resetQuiescence();

			// schedule a new reminder to Gossip
			gossipTimeout = sendToSelf(new GossipReminder(), gossipTime);

		} else {
			log.info("Gossip stopped (no correct node to gossip)");
		}
	}

	private void onGossip(Gossip msg) {
		log.debug("gossiped by {}, beats={}, push_pull={}", idFromRef(getSender()), nodes.beatsToString(), pullByGossip);

		// this method update both the beats for all nodes and resets the quiescence for any updated
		updateBeats(msg.getBeats());

		if (pullByGossip) {

			// gossip back (pull strategy)
			reply(new GossipReply(nodes.getBeats()));
		}
	}

	private void onGossipReply(GossipReply msg) {
		updateBeats(msg.getBeats());
		log.debug("gossiped (reply) by {}", idFromRef(getSender()));
	}

	/**
	 * This method is called when the failure detector detects
	 * that a node is crashed. This is a self-message.
	 *
	 * @param msg Details with the node that is though to be crashed.
	 */
	private void onFail(Fail msg) {
		ActorRef failing = msg.getFailing();
		long failId = msg.getFailId();

		// check if the Fail message was still valid
		if (nodes.get(failing).getTimeoutId() == failId) {
			// remove from correct nodes and report to Tracker
			nodes.setFailed(failing);
			sendToTracker(new CrashReport(failing));
			log.info("Node {} reported as failed", idFromRef(failing));
		} else {
			log.warning("Dropped Fail message (expected Id: {}, found: {}) -> {}",
				nodes.get(failing).getTimeoutId(), failId, msg.toString());
		}

		// schedule message to remove the node from the heartbeat map
		Cleanup cleanMsg = new Cleanup(failing, nodes.get(failing).getTimeoutId() + 1);
		nodes.get(failing).resetTimeout(sendToSelf(cleanMsg, cleanupTime));
	}

	private void onCleanup(Cleanup msg) {
		ActorRef failed = msg.getFailed();
		long cleanId = msg.getCleanId();

		// check if the Cleanup message was still valid
		if (nodes.get(failed).getTimeoutId() == cleanId) {
			nodes.remove(failed);
			log.info("Node {} cleanup", idFromRef(failed));
		} else {
			log.warning("Dropped Cleanup message (expected Id: {}, found: {}) -> {}",
				nodes.get(failed).getTimeoutId(), cleanId, msg.toString());
		}
	}

	private void sendMulticast() {
		// evaluate probability of sending (send for sure if Wait = MaxWait)
		double multicastProb = Math.pow((double) multicastWait / multicastMaxWait, multicastParam);
		double rand = Math.random();

		if (rand < multicastProb) {
			// do multicast
			nodes.get(getSelf()).heartbeat();
			multicastWait = 0;
			multicast(new CatastropheMulticast(nodes.getBeats()));

			// TODO: check that this works with the experiments
			// even the probability of gossip to any node
			nodes.getCorrectNodes().forEach(ref -> nodes.get(ref).resetQuiescence());
			log.debug("multicast: " + nodes.beatsToString());
		} else {
			// multicast postponed
			multicastWait++;
			sendToSelf(new CatastropheReminder(), 1000);
		}
	}

	/**
	 * This method is called when this node receive a message that was
	 * sent in multicast to all nodes.
	 *
	 * @param msg Message.
	 */
	private void onMulticast(CatastropheMulticast msg) {
		multicastWait = 0;
		updateBeats(msg.getBeats());
	}

	private void updateBeats(Map<ActorRef, Long> gossipedBeats) {
		nodes.getCorrectNodes().forEach(ref -> {
			long gossipedBeatCount = gossipedBeats.get(ref);
			// if a higher heartbeat counter was gossiped, update it
			if (gossipedBeatCount > nodes.get(ref).getBeatCount()) {
				nodes.get(ref).setBeatCount(gossipedBeatCount);
				// lower the probability of gossiping the same node soon
				nodes.get(ref).resetQuiescence();
				// restart the timeout
				Fail failMsg = new Fail(ref, nodes.get(ref).getTimeoutId() + 1);
				nodes.get(ref).resetTimeout(sendToSelf(failMsg, failTime));
			} else {
				// no heartbeat update (will increase probability of gossip)
				nodes.get(ref).quiescent();
			}
		});
	}

	/**
	 * Send a message to the tracker.
	 *
	 * @param message Message to send.
	 */
	private void sendToTracker(Serializable message) {
		getContext().actorSelection(trackerAddress).tell(message, getSelf());
	}

	/**
	 * Send a message to self.
	 *
	 * @param message Message to send.
	 * @param delay   Schedule to be sent after delay milliseconds.
	 * @return Cancellable timeout.
	 */
	private Cancellable sendToSelf(Serializable message, long delay) {
		return getContext().system().scheduler().scheduleOnce(
			Duration.create(delay, TimeUnit.MILLISECONDS),
			getSelf(),
			message,
			getContext().system().dispatcher(),
			getSelf()
		);
	}

	/**
	 * Reply to the actor that sent the last message.
	 *
	 * @param message Message to sent back.
	 */
	private void reply(Serializable message) {
		getSender().tell(message, getSelf());
	}

	/**
	 * Send the given message to all the other nodes.
	 *
	 * @param message Message to send in multicast.
	 */
	private void multicast(Serializable message) {
		nodes.getCorrectNodes().forEach(ref -> ref.tell(message, getSelf()));
	}
}
