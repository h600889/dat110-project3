/**
 * 
 */
package no.hvl.dat110.middleware;

import java.math.BigInteger;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import no.hvl.dat110.rpc.interfaces.NodeInterface;
import no.hvl.dat110.util.LamportClock;
import no.hvl.dat110.util.Util;

/**
 * @author tdoy
 *
 */
public class MutualExclusion {
		
	private static final Logger logger = LogManager.getLogger(MutualExclusion.class);
	/** lock variables */
	private boolean CS_BUSY = false;						// indicate to be in critical section (accessing a shared resource) 
	private boolean WANTS_TO_ENTER_CS = false;				// indicate to want to enter CS
	private List<Message> queueack; 						// queue for acknowledged messages
	private List<Message> mutexqueue;						// queue for storing process that are denied permission. We really don't need this for quorum-protocol
	
	private LamportClock clock;								// lamport clock
	private Node node;
	
	public MutualExclusion(Node node) throws RemoteException {
		this.node = node;
		
		clock = new LamportClock();
		queueack = new ArrayList<Message>();
		mutexqueue = new ArrayList<Message>();
	}
	
	public synchronized void acquireLock() {
		CS_BUSY = true;
	}
	
	public void releaseLocks() {
		WANTS_TO_ENTER_CS = false;
		CS_BUSY = false;
	}

	public boolean doMutexRequest(Message message, byte[] updates) throws RemoteException {
		
		logger.info(node.nodename + " wants to access CS");
		//TODO: //implement the mutex request algorithm
		// clear the queueack before requesting for votes
		// clear the mutexqueue
		// increment clock
		// adjust the clock on the message, by calling the setClock on the message
		// wants to access resource - set the appropriate lock variable
		queueack.clear();
		mutexqueue.clear();
		clock.increment();
		message.setClock(clock.getClock());
		WANTS_TO_ENTER_CS = true;
		// start MutualExclusion algorithm
		// first, call removeDuplicatePeersBeforeVoting. A peer can hold/contain 2 replicas of a file. This peer will appear twice
		// multicast the message to activenodes (hint: use multicastMessage)
		// check that all replicas have replied (permission)
		// if yes, acquireLock
		// node.broadcastUpdatetoPeers
		// clear the mutexqueue
		// return permission
		List<Message> peers = removeDuplicatePeersBeforeVoting();
		multicastMessage(message, peers);
		boolean permission = areAllMessagesReturned(peers.size());
		if (permission) {
			acquireLock();
			node.broadcastUpdatetoPeers(updates);
			mutexqueue.clear();
		}

		return permission;
	}
	
	// multicast message to other processes including self
	private void multicastMessage(Message message, List<Message> activenodes) throws RemoteException {
		
		logger.info("Number of peers to vote = "+activenodes.size());
		
		// iterate over the activenodes
		// obtain a stub for each node from the registry
		// call onMutexRequestReceived()
		for (Message m : activenodes) {
			NodeInterface stub = Util.getProcessStub(m.getNodeName(), m.getPort());
			stub.onMutexRequestReceived(message);
		}
		
	}
	
	public void onMutexRequestReceived(Message message) throws RemoteException {
		
		// increment the local clock
		// if message is from self, acknowledge, and call onMutexAcknowledgementReceived()
		clock.increment();
		if (message.getNodeName().equals(node.getNodeName())) {
			message.setClock(clock.getClock());
			onMutexAcknowledgementReceived(message);
		}
		int caseid = -1;

		/* write if statement to transition to the correct caseid */
		// caseid=0: Receiver is not accessing shared resource and does not want to (send OK to sender)
		// caseid=1: Receiver already has access to the resource (dont reply but queue the request)
		// caseid=2: Receiver wants to access resource but is yet to - compare own message clock to received message's clock
		if (!CS_BUSY && !WANTS_TO_ENTER_CS) {
			caseid = 0;
		} else if (CS_BUSY) {
			caseid = 1;
		} else if (WANTS_TO_ENTER_CS) {
			caseid = 2;
		}

		// check for decision
		doDecisionAlgorithm(message, mutexqueue, caseid);
	}
	
	public void doDecisionAlgorithm(Message message, List<Message> queue, int condition) throws RemoteException {
		
		String procName = message.getNodeName();
		int port = message.getPort();
		
		switch(condition) {
		
			/** case 1: Receiver is not accessing shared resource and does not want to (send OK to sender) */
			case 0: {
				
				// get a stub for the sender from the registry
				// acknowledge message
				// send acknowledgement back by calling onMutexAcknowledgementReceived()
				NodeInterface stub = Util.getProcessStub(procName, port);
				message.setAcknowledged(true);
				stub.onMutexAcknowledgementReceived(message);

				break;
			}
		
			/** case 2: Receiver already has access to the resource (dont reply but queue the request) */
			case 1: {
				
				// queue this message
				queue.add(message);
				break;
			}
			
			/**
			 *  case 3: Receiver wants to access resource but is yet to (compare own message clock to received message's clock
			 *  the message with lower timestamp wins) - send OK if received is lower. Queue message if received is higher
			 */
			case 2: {
				
				// check the clock of the sending process (note that the correct clock is in the message)
				// own clock for the multicast message (note that the correct clock is in the message)
				// compare clocks, the lowest wins
				// if clocks are the same, compare nodeIDs, the lowest wins
				// if sender wins, acknowledge the message, obtain a stub and call onMutexAcknowledgementReceived()
				// if sender looses, queue it
				int senderClock = message.getClock();
				int ownClock = node.getMessage().getClock();

				if (senderClock < ownClock || senderClock == ownClock && message.getNodeID().compareTo(node.getNodeID()) < 0) {
					message.setAcknowledged(true);
					NodeInterface stub = Util.getProcessStub(procName, port);
					stub.onMutexAcknowledgementReceived(message);
				} else {
					queue.add(message);
				}

				break;
			}
			
			default: break;
		}
		
	}
	
	public void onMutexAcknowledgementReceived(Message message) throws RemoteException {
		
		// add message to queueack
		queueack.add(message);
	}
	
	// multicast release locks message to other processes including self
	public void multicastReleaseLocks(Set<Message> activenodes) {
		logger.info("Releasing locks from = "+activenodes.size());
		
		// iterate over the activenodes
		// obtain a stub for each node from the registry
		// call releaseLocks()

		for (Message message : activenodes) {
			NodeInterface stub = Util.getProcessStub(message.getNodeName(), message.getPort());
			try {
				stub.releaseLocks();
			} catch (RemoteException e) {
				e.printStackTrace();
			}
		}
	}
	
	private boolean areAllMessagesReturned(int numvoters) throws RemoteException {
		logger.info(node.getNodeName()+": size of queueack = "+queueack.size());
		
		// check if the size of the queueack is same as the numvoters
		// clear the queueack
		// return true if yes and false if no
		if (queueack.size() == numvoters) {
			queueack.clear();
			return true;
		} else {
			return false;
		}
	}
	
	private List<Message> removeDuplicatePeersBeforeVoting() {
		
		List<Message> uniquepeer = new ArrayList<Message>();
		for(Message p : node.activenodesforfile) {
			boolean found = false;
			for(Message p1 : uniquepeer) {
				if(p.getNodeName().equals(p1.getNodeName())) {
					found = true;
					break;
				}
			}
			if(!found)
				uniquepeer.add(p);
		}		
		return uniquepeer;
	}
}