package net.tomp2p.relay;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReferenceArray;

import net.tomp2p.connection.ChannelCreator;
import net.tomp2p.connection.PeerConnection;
import net.tomp2p.futures.BaseFutureAdapter;
import net.tomp2p.futures.FutureChannelCreator;
import net.tomp2p.futures.FutureDone;
import net.tomp2p.futures.FutureForkJoin;
import net.tomp2p.futures.FuturePeerConnection;
import net.tomp2p.p2p.Peer;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.peers.PeerSocketAddress;
import net.tomp2p.relay.android.AndroidRelayConnection;
import net.tomp2p.relay.tcp.OpenTCPRelayConnection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The relay manager is responsible for setting up and maintaining connections
 * to relay peers and contains all information about the relays.
 * 
 * @author Raphael Voellmy
 * @author Thomas Bocek
 * @author Nico Rutishauser
 * 
 */
public class DistributedRelay {

	final static Logger LOG = LoggerFactory.getLogger(DistributedRelay.class);

	final private Peer peer;
	final private RelayRPC relayRPC;

	final private Collection<BaseRelayConnection> relays;
	final private Collection<PeerAddress> failedRelays;

	private final Collection<RelayListener> relayListeners;
	private final FutureChannelCreator futureChannelCreator;
	private final RelayType relayType;
	private final String gcmRegistrationId;

	/**
	 * @param peer
	 *            the unreachable peer
	 * @param relayRPC
	 *            the relay RPC
	 * @param maxRelays
	 *            maximum number of relay peers to set up
	 * @param relayType
	 * 			  the kind of the relay connection
	 */
	public DistributedRelay(final Peer peer, RelayRPC relayRPC, int failedRelayWaitTime, RelayType relayType, String gcmRegistrationId) {
		this.peer = peer;
		this.relayRPC = relayRPC;
		this.relayType = relayType;
		this.gcmRegistrationId = gcmRegistrationId;

		relays = Collections.synchronizedList(new ArrayList<BaseRelayConnection>());
		failedRelays = new ConcurrentCacheSet<PeerAddress>(failedRelayWaitTime);
		relayListeners = Collections.synchronizedList(new ArrayList<RelayListener>(1));
		
		// this needs to be kept open, as we want the peerconnection to stay alive
		futureChannelCreator = peer.connectionBean().reservation().create(0, PeerAddress.MAX_RELAYS);
	}

	/**
	 * Returns addresses of current relay peers
	 * 
	 * @return Collection of PeerAddresses of the relay peers
	 */
	public Collection<BaseRelayConnection> relays() {
		return relays;
	}

	public void addRelayListener(RelayListener relayListener) {
		synchronized (relayListeners) {
			relayListeners.add(relayListener);
		}
	}

	public FutureForkJoin<FutureDone<Void>> shutdown() {
		final AtomicReferenceArray<FutureDone<Void>> futureDones2;
		synchronized (relays) {
			@SuppressWarnings("unchecked")
			FutureDone<Void>[] futureDones = new FutureDone[relays.size() + 1];
			futureDones2 = new AtomicReferenceArray<FutureDone<Void>>(futureDones);
			
			int i = 1;
			for (BaseRelayConnection relay : relays) {
				futureDones2.set(i++, relay.shutdown());
			}
		}
		

		final FutureDone<Void> futureChannelShutdown = new FutureDone<Void>();
		futureDones2.set(0, futureChannelShutdown);
		synchronized (relayListeners) {
			relayListeners.clear();
		}
		
		futureChannelCreator.addListener(new BaseFutureAdapter<FutureChannelCreator>() {
			@Override
			public void operationComplete(FutureChannelCreator future) throws Exception {
				future.channelCreator().shutdown().addListener(new BaseFutureAdapter<FutureDone<Void>>() {
					@Override
					public void operationComplete(FutureDone<Void> future) throws Exception {
						futureChannelShutdown.done();
					}
				});
			}
		});

		return new FutureForkJoin<FutureDone<Void>>(futureDones2);
	}

	/**
	 * Sets up relay connections to other peers. The number of relays to set up
	 * is determined by {@link PeerAddress#MAX_RELAYS} or passed to the
	 * constructor of this class. It is important that we call this after we
	 * bootstrapped and have recent information in our peer map.
	 * 
	 * @return RelayFuture containing a {@link DistributedRelay} instance
	 */
	public FutureRelay setupRelays(final FutureRelay futureRelay, final Collection<PeerAddress> manualRelays,
	        final int maxFail) {

		futureChannelCreator.addListener(new BaseFutureAdapter<FutureChannelCreator>() {
			public void operationComplete(final FutureChannelCreator future) throws Exception {
				if (future.isSuccess()) {
					final Collection<PeerAddress> relayCandidates;
					if (manualRelays.isEmpty()) {
						//Get the neighbors of this peer that could possibly act as relays. Relay
						// candidates are neighboring peers that are not relayed themselves and have
						// not recently failed as relay or denied acting as relay.
						relayCandidates = peer.distributedRouting().peerMap().all();
						//remove those who we know have failed
						relayCandidates.removeAll(failedRelays);
					} else {
						// if the user sets manual relays, the failed relays are not removed, as this has to be done by the user 
						relayCandidates = new ArrayList<PeerAddress>(manualRelays);
					}
					
					filter(relayCandidates);
					setupPeerConnections(futureRelay, relayCandidates, maxFail);
				} else {
					futureRelay.failed(future);
				}
			}
		});
		return futureRelay;
	}

	/**
	 * Remove recently failed relays, peers that are relayed themselves and
	 * peers that are already relays
	 */
	private void filter(Collection<PeerAddress> relayCandidates) {
		for (Iterator<PeerAddress> iterator = relayCandidates.iterator(); iterator.hasNext();) {
			PeerAddress pa = iterator.next();
			
			// filter peers that are relayed themselves
			if (pa.isRelayed()) {
				iterator.remove();
				continue;
			}
			
			// filter relays that are already connected
			synchronized (relays) {
				for (BaseRelayConnection relay : relays) {
					if (relay.relayAddress().equals(pa)) {
						iterator.remove();
						break;
					}
				}
			}
		}
		
		LOG.debug("Found {} peers that could act as relays", relayCandidates.size());
	}

	/**
	 * Sets up N peer connections to relay candidates, where N is maxRelays
	 * minus the current relay count.
	 * 
	 * @param cc
	 * @return FutureDone
	 */
	private void setupPeerConnections(final FutureRelay futureRelay, Collection<PeerAddress> relayCandidates, final int maxFail) {
		final int nrOfRelays = Math.min(PeerAddress.MAX_RELAYS - relays.size(), relayCandidates.size());
		LOG.debug("Setting up {} relays", nrOfRelays);
		if (nrOfRelays > 0) {
			@SuppressWarnings("unchecked")
			FutureDone<PeerConnection>[] futureDones = new FutureDone[nrOfRelays];
			AtomicReferenceArray<FutureDone<PeerConnection>> relayConnectionFutures = new AtomicReferenceArray<FutureDone<PeerConnection>>(futureDones);
			setupPeerConnectionsRecursive(relayConnectionFutures, relayCandidates, nrOfRelays, futureRelay, 0, maxFail, new StringBuilder());
		} else {
			if(relayCandidates.size() == 0) {
				//no candidates
				futureRelay.failed("done");
			} else {
				//nothing to do
				futureRelay.done(Collections.<BaseRelayConnection>emptyList());
			}
		}
	}

	/**
	 * Sets up connections to relay peers recursively. If the maximum number of
	 * relays is already reached, this method will do nothing.
	 * 
	 * @param futureRelayConnections
	 * @param relayCandidates
	 *            List of peers that could act as relays
	 * @param numberOfRelays
	 *            The number of relays to establish.
	 * @param futureDone
	 * @return
	 * @throws InterruptedException
	 */
	private void setupPeerConnectionsRecursive(final AtomicReferenceArray<FutureDone<PeerConnection>> futures,
	        final Collection<PeerAddress> relayCandidates, final int numberOfRelays,
	        final FutureRelay futureRelay, final int fail, final int maxFail, 
	        final StringBuilder status) {
		int active = 0;
		for (int i = 0; i < numberOfRelays; i++) {
			if (futures.get(i) == null) {
				PeerAddress candidate = null;
				synchronized (relayCandidates) {
					if (!relayCandidates.isEmpty()) {
						candidate = relayCandidates.iterator().next();
						relayCandidates.remove(candidate);
					}
				}
				if(candidate !=null) {
					final FuturePeerConnection fpc = peer.createPeerConnection(candidate);
					FutureDone<PeerConnection> futureDone = relayRPC.setupRelay(fpc, relayType, gcmRegistrationId);
					setupAddRelays(fpc.remotePeer(), futureDone);
					futures.set(i, futureDone);
					active++;
				}
			} else {
				active++;
			}
		}
		if (active == 0) {
			updatePeerAddress();
			futureRelay.failed("No candidates: " + status.toString());
			return;
		}
		if (fail > maxFail) {
			updatePeerAddress();
			futureRelay.failed("Maxfail: " + status.toString());
			return;
		}

		FutureForkJoin<FutureDone<PeerConnection>> ffj = new FutureForkJoin<FutureDone<PeerConnection>>(active, false, futures);

		ffj.addListener(new BaseFutureAdapter<FutureForkJoin<FutureDone<PeerConnection>>>() {
			public void operationComplete(FutureForkJoin<FutureDone<PeerConnection>> futureForkJoin) throws Exception {
				if (futureForkJoin.isSuccess()) {
					updatePeerAddress();
					synchronized (relays) {
						futureRelay.done(new ArrayList<BaseRelayConnection>(relays));
					}
				} else if (!peer.isShutdown()) {
					setupPeerConnectionsRecursive(futures, relayCandidates, numberOfRelays, futureRelay,
					        fail + 1, maxFail, status.append(futureForkJoin.failedReason()).append(" "));
				} else {
					futureRelay.failed(futureForkJoin);
				}
			}
		});
	}

	private void setupAddRelays(final PeerAddress remotePeer, final FutureDone<PeerConnection> futureDone) {
		futureDone.addListener(new BaseFutureAdapter<FutureDone<PeerConnection>>() {
			@Override
			public void operationComplete(FutureDone<PeerConnection> future) throws Exception {
				if (future.isSuccess()) {
					PeerConnection peerConnection = future.object();
					PeerAddress relayAddress = peerConnection.remotePeer();

					BaseRelayConnection connection = null;
					switch (relayType) {
						case OPENTCP:
							connection = new OpenTCPRelayConnection(peerConnection, peer, relayRPC.config());
							break;
						case ANDROID:
							connection = new AndroidRelayConnection(relayAddress);
							break;
						default:
							LOG.error("Unknown relay type");
							break;
					}
					addCloseListener(connection);
					
					synchronized (relays) {
						if(relays.size() >= 5) {
							// number of limit exceeded
							return;
						}
						
						LOG.debug("Adding peer {} as a relay", relayAddress);
						relays.add(connection);
					}
				} else {
					LOG.debug("Peer {} denied relay request", remotePeer);
					failedRelays.add(remotePeer);
					futureDone.failed(future);
				}
			}
		});
	}

	/**
	 * Adds a close listener for an open peer connection, so that if the
	 * connection to the relay peer drops, a new relay is found and a new relay
	 * connection is established
	 * 
	 * @param connection
	 *            the relay connection on which to add a close listener
	 */
	private void addCloseListener(final BaseRelayConnection connection) {
		connection.addCloseListener(new RelayListener() {
			@Override
			public void relayFailed(PeerAddress relayAddress) {
				// used to remove a relay peer from the unreachable peers
				// peer address. It will <strong>not</strong> cut the
				// connection to an existing peer, but only update the
				// unreachable peer's PeerAddress if a relay peer failed.
				// It will also cancel the {@link PeerMapUpdateTask}
				// maintenance task if the last relay is removed.
				relays.remove(connection);
				failedRelays.add(relayAddress);
				updatePeerAddress();
				
				synchronized (relayListeners) {
					for (RelayListener relayListener : relayListeners) {
						relayListener.relayFailed(relayAddress);
					}
				}
			}
		});
	}

	/**
	 * Updates the peer's PeerAddress: Adds the relay addresses to the peer
	 * address, updates the firewalled flags, and bootstraps to announce its new
	 * relay peers.
	 */
	private void updatePeerAddress() {
		// add relay addresses to peer address
		boolean hasRelays = !relays.isEmpty();

		Collection<PeerSocketAddress> socketAddresses = new ArrayList<PeerSocketAddress>(relays.size());
		synchronized (relays) {
			for (BaseRelayConnection relay : relays) {
				PeerAddress pa = relay.relayAddress();
				socketAddresses.add(new PeerSocketAddress(pa.inetAddress(), pa.tcpPort(), pa.udpPort()));
			}
		}

		// update firewalled and isRelayed flags
		PeerAddress newAddress = peer.peerAddress().changeFirewalledTCP(!hasRelays).changeFirewalledUDP(!hasRelays)
		        .changeRelayed(hasRelays).changePeerSocketAddresses(socketAddresses);
		peer.peerBean().serverPeerAddress(newAddress);
		LOG.debug("Updated peer address {}, isrelay = {}", newAddress, hasRelays);
	}
}
