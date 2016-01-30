/*
 *  ProtocolLib - Bukkit server library that allows access to the Minecraft protocol.
 *  Copyright (C) 2012 Kristian S. Stangeland
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU General Public License as published by the Free Software Foundation; either version 2 of
 *  the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with this program;
 *  if not, write to the Free Software Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA
 *  02111-1307 USA
 */

package com.comphenix.protocol.async;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import com.comphenix.protocol.PacketStream;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.NetworkMarker;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.injector.PrioritizedListener;
import com.comphenix.protocol.reflect.FieldAccessException;
import com.comphenix.protocol.reflect.FuzzyReflection;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.utility.MinecraftVersion;
import com.google.common.primitives.Longs;

/**
 * Contains information about the packet that is being processed by asynchronous listeners.
 * <p>
 * Asynchronous listeners can use this to set packet timeout or transmission order.
 * 
 * @author Kristian
 */
public class AsyncMarker implements Serializable, Comparable<AsyncMarker> {
		
	/**
	 * Generated by Eclipse.
	 */
	private static final long serialVersionUID = -2621498096616187384L;

	/**
	 * Default number of milliseconds until a packet will rejected.
	 */
	public static final int DEFAULT_TIMEOUT_DELTA = 1800 * 1000;
	
	/**
	 * Default number of packets to skip.
	 */
	public static final int DEFAULT_SENDING_DELTA = 0;
	
	/**
	 * The packet stream responsible for transmitting the packet when it's done processing.
	 */
	private transient PacketStream packetStream;
	
	/**
	 * Current list of async packet listeners.
	 */
	private transient Iterator<PrioritizedListener<AsyncListenerHandler>> listenerTraversal;
	
	// Timeout handling
	private long initialTime;
	private long timeout;
	
	// Packet order
	private long originalSendingIndex;
	private long newSendingIndex;
	
	// Used to determine if a packet must be reordered in the sending queue
	private Long queuedSendingIndex;
	
	// Whether or not the packet has been processed by the listeners
	private volatile boolean processed;
	
	// Whether or not the packet has been sent
	private volatile boolean transmitted;
	
	// Whether or not the asynchronous processing itself should be cancelled
	private volatile boolean asyncCancelled;
	
		// Whether or not to delay processing
	private AtomicInteger processingDelay = new AtomicInteger();
	
	// Used to synchronize processing on the shared PacketEvent
	private Object processingLock = new Object();
	
	// Used to identify the asynchronous worker
	private transient AsyncListenerHandler listenerHandler;
	private transient int workerID;
	
	// Determine if Minecraft processes this packet asynchronously
	private volatile static Method isMinecraftAsync;
	private volatile static boolean alwaysSync;

	/**
	 * Create a container for asyncronous packets.
	 * @param initialTime - the current time in milliseconds since 01.01.1970 00:00.
	 */
	AsyncMarker(PacketStream packetStream, long sendingIndex, long initialTime, long timeoutDelta) {
		if (packetStream == null)
			throw new IllegalArgumentException("packetStream cannot be NULL");
		
		this.packetStream = packetStream;
		
		// Timeout
		this.initialTime = initialTime;
		this.timeout = initialTime + timeoutDelta;
		
		// Sending index
		this.originalSendingIndex = sendingIndex;
		this.newSendingIndex = sendingIndex;
	}
	
	/**
	 * Retrieve the time the packet was initially queued for asynchronous processing.
	 * @return The initial time in number of milliseconds since 01.01.1970 00:00.
	 */
	public long getInitialTime() {
		return initialTime;
	}

	/**
	 * Retrieve the time the packet will be forcefully rejected.
	 * @return The time to reject the packet, in milliseconds since 01.01.1970 00:00.
	 */
	public long getTimeout() {
		return timeout;
	}
	
	/**
	 * Set the time the packet will be forcefully rejected.
	 * @param timeout - time to reject the packet, in milliseconds since 01.01.1970 00:00.
	 */
	public void setTimeout(long timeout) {
		this.timeout = timeout;
	}

	/**
	 * Retrieve the order the packet was originally transmitted.
	 * @return The original packet index.
	 */
	public long getOriginalSendingIndex() {
		return originalSendingIndex;
	}

	/**
	 * Retrieve the desired sending order after processing has completed.
	 * <p>
	 * Higher sending order means lower priority.
	 * @return Desired sending order.
	 */
	public long getNewSendingIndex() {
		return newSendingIndex;
	}

	/**
	 * Sets the desired sending order after processing has completed.
	 * <p>
	 * Higher sending order means lower priority.
	 * @param newSendingIndex - new packet send index.
	 */
	public void setNewSendingIndex(long newSendingIndex) {
		this.newSendingIndex = newSendingIndex;
	}

	/**
	 * Retrieve the packet stream responsible for transmitting this packet.
	 * @return The packet stream.
	 */
	public PacketStream getPacketStream() {
		return packetStream;
	}

	/**
	 * Sets the output packet stream responsible for transmitting this packet.
	 * @param packetStream - new output packet stream.
	 */
	public void setPacketStream(PacketStream packetStream) {
		this.packetStream = packetStream;
	}

	/**
	 * Retrieve whether or not this packet has been processed by the async listeners.
	 * @return TRUE if it has been processed, FALSE otherwise.
	 */
	public boolean isProcessed() {
		return processed;
	}

	/**
	 * Sets whether or not this packet has been processed by the async listeners.
	 * @param processed - TRUE if it has, FALSE otherwise.
	 */
	void setProcessed(boolean processed) {
		this.processed = processed;
	}

	/**
	 * Increment the number of times the current packet must be signalled as done before its transmitted.
	 * <p>
	 * This is useful if an asynchronous listener is waiting for further information before the
	 * packet can be sent to the user. A packet listener <b>MUST</b> eventually call
	 * {@link AsyncFilterManager#signalPacketTransmission(PacketEvent)},
	 * even if the packet is cancelled, after this method is called.
	 * <p>
	 * It is recommended that processing outside a packet listener is wrapped in a synchronized block
	 * using the {@link #getProcessingLock()} method.
	 * 
	 * @return The new processing delay.
	 */
	public int incrementProcessingDelay() {
		return processingDelay.incrementAndGet();
	}
	
	/**
	 * Decrement the number of times this packet must be signalled as done before it's transmitted.
	 * @return The new processing delay. If zero, the packet should be sent.
	 */
	int decrementProcessingDelay() {
		return processingDelay.decrementAndGet();
	}
	
	/**
	 * Retrieve the number of times a packet must be signalled to be done before it's sent.
	 * @return Number of processing delays.
	 */
	public int getProcessingDelay() {
		return processingDelay.get();
	}
	
	/**
	 * Whether or not this packet is or has been queued for processing.
	 * @return TRUE if it has, FALSE otherwise.
	 */
	public boolean isQueued() {
		return queuedSendingIndex != null;
	}

	/**
	 * Retrieve the sending index when the packet was queued.
	 * @return Queued sending index.
	 */
	public long getQueuedSendingIndex() {
		return queuedSendingIndex != null ? queuedSendingIndex : 0;
	}

	/**
	 * Set the sending index when the packet was queued.
	 * @param queuedSendingIndex - sending index.
	 */
	void setQueuedSendingIndex(Long queuedSendingIndex) {
		this.queuedSendingIndex = queuedSendingIndex;
	}

	/**
	 * Processing lock used to synchronize access to the parent PacketEvent and PacketContainer.
	 * <p>
	 * This lock is automatically acquired for every asynchronous packet listener. It should only be
	 * used to synchronize access to a PacketEvent if it's processing has been delayed.
	 * @return A processing lock.
	 */
	public Object getProcessingLock() {
		return processingLock;
	}

	public void setProcessingLock(Object processingLock) {
		this.processingLock = processingLock;
	}

	/**
	 * Retrieve whether or not this packet has already been sent.
	 * @return TRUE if it has been sent before, FALSE otherwise.
	 */
	public boolean isTransmitted() {
		return transmitted;
	}

	/**
	 * Determine if this packet has expired.
	 * @return TRUE if it has, FALSE otherwise.
	 */
	public boolean hasExpired() {
		return hasExpired(System.currentTimeMillis());
	}
	
	/**
	 * Determine if this packet has expired given this time.
	 * @param currentTime - the current time in milliseconds since 01.01.1970 00:00.
	 * @return TRUE if it has, FALSE otherwise.
	 */
	public boolean hasExpired(long currentTime) {
		return timeout < currentTime;
	}
	
	/**
	 * Determine if the asynchronous handling should be cancelled.
	 * @return TRUE if it should, FALSE otherwise.
	 */
	public boolean isAsyncCancelled() {
		return asyncCancelled;
	}

	/**
	 * Set whether or not the asynchronous handling should be cancelled.
	 * <p>
	 * This is only relevant during the synchronous processing. Asynchronous
	 * listeners should use the normal cancel-field to cancel a PacketEvent.
	 * 
	 * @param asyncCancelled - TRUE to cancel it, FALSE otherwise.
	 */
	public void setAsyncCancelled(boolean asyncCancelled) {
		this.asyncCancelled = asyncCancelled;
	}

	/**
	 * Retrieve the current asynchronous listener handler.
	 * @return Asychronous listener handler, or NULL if this packet is not asynchronous.
	 */
	public AsyncListenerHandler getListenerHandler() {
		return listenerHandler;
	}

	/**
	 * Set the current asynchronous listener handler.
	 * <p>
	 * Used by the worker to update the value.
	 * @param listenerHandler - new listener handler.
	 */
	void setListenerHandler(AsyncListenerHandler listenerHandler) {
		this.listenerHandler = listenerHandler;
	}

	/**
	 * Retrieve the current worker ID.
	 * @return Current worker ID.
	 */
	public int getWorkerID() {
		return workerID;
	}

	/**
	 * Set the current worker ID.
	 * <p>
	 * Used by the worker.
	 * @param workerID - new worker ID.
	 */
	void setWorkerID(int workerID) {
		this.workerID = workerID;
	}

	/**
	 * Retrieve iterator for the next listener in line.
	 * @return Next async packet listener iterator.
	 */
	Iterator<PrioritizedListener<AsyncListenerHandler>> getListenerTraversal() {
		return listenerTraversal;
	}
	
	/**
	 * Set the iterator for the next listener.
	 * @param listenerTraversal - the new async packet listener iterator.
	 */
	void setListenerTraversal(Iterator<PrioritizedListener<AsyncListenerHandler>> listenerTraversal) {
		this.listenerTraversal = listenerTraversal;
	}
	
	/**
	 * Transmit a given packet to the current packet stream.
	 * @param event - the packet to send.
	 * @throws IOException If the packet couldn't be sent.
	 */
	void sendPacket(PacketEvent event) throws IOException {
		try {
			if (event.isServerPacket()) {
				packetStream.sendServerPacket(event.getPlayer(), event.getPacket(), NetworkMarker.getNetworkMarker(event), false);
			} else {
				packetStream.recieveClientPacket(event.getPlayer(), event.getPacket(), NetworkMarker.getNetworkMarker(event), false);
			}
			transmitted = true;
			
		} catch (InvocationTargetException e) {
			throw new IOException("Cannot send packet", e);
		} catch (IllegalAccessException e) {
			throw new IOException("Cannot send packet", e);
		}
	}
	
	/**
	 * Determine if Minecraft allows asynchronous processing of this packet.
	 * @param event - packet event
	 * @return TRUE if it does, FALSE otherwise.
	 * @throws FieldAccessException If determining fails for some reasaon
	 */
	public boolean isMinecraftAsync(PacketEvent event) throws FieldAccessException {
		if (isMinecraftAsync == null && !alwaysSync) {
			try {
				isMinecraftAsync = FuzzyReflection.fromClass(MinecraftReflection.getPacketClass()).getMethodByName("a_.*");
			} catch (RuntimeException e) {
				// This will occur in 1.2.5 (or possibly in later versions)
				List<Method> methods = FuzzyReflection.fromClass(MinecraftReflection.getPacketClass()).
										getMethodListByParameters(boolean.class, new Class[] {});
				
				// Try to look for boolean methods
				if (methods.size() == 2) {
					isMinecraftAsync = methods.get(1);
				} else if (methods.size() == 1) {
					// We're in 1.2.5
					alwaysSync = true;
				} else if (MinecraftVersion.getCurrentVersion().isAtLeast(MinecraftVersion.BOUNTIFUL_UPDATE)) {
					// The centralized async marker was removed in 1.8
					// Incoming chat packets can be async
					if (event.getPacketType() == PacketType.Play.Client.CHAT) {
						String content = event.getPacket().getStrings().readSafely(0);
						if (content != null) {
							// Incoming chat packets are async only if they aren't commands
							return ! content.startsWith("/");
						} else {
							ProtocolLibrary.log(Level.WARNING, "Failed to determine contents of incoming chat packet!");
							alwaysSync = true;
						}
					} else {
						// TODO: Find more cases of async packets
						return false;
					}
				} else {
					ProtocolLibrary.log(Level.INFO, "Could not determine asynchronous state of packets (this can probably be ignored)");
					alwaysSync = true;
				}
			}
		}

		if (alwaysSync) {
			return false;
		} else {
			try {
				// Wrap exceptions
				return (Boolean) isMinecraftAsync.invoke(event.getPacket().getHandle());
			} catch (IllegalArgumentException e) {
				throw new FieldAccessException("Illegal argument", e);
			} catch (IllegalAccessException e) {
				throw new FieldAccessException("Unable to reflect method call 'a_', or: isAsyncPacket.", e);
			} catch (InvocationTargetException e) {
				throw new FieldAccessException("Minecraft error", e);
			}
		}
	}
	
	@Override
	public int compareTo(AsyncMarker o) {
		if (o == null)
			return 1;
		else
			return Longs.compare(getNewSendingIndex(), o.getNewSendingIndex());
	}
	
	@Override
	public boolean equals(Object other) {
		// Standard equals
		if (other == this)
			return true;
		if (other instanceof AsyncMarker)
			return getNewSendingIndex() == ((AsyncMarker) other).getNewSendingIndex();
		else
			return false;
	}
	
	@Override
	public int hashCode() {
		return Longs.hashCode(getNewSendingIndex());
	}
}
