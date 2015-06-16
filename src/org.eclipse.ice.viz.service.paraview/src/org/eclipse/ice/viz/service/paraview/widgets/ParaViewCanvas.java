/*******************************************************************************
 * Copyright (c) 2015 UT-Battelle, LLC.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Jordan Deyton (UT-Battelle, LLC.) - initial API and implementation and/or 
 *      initial documentation
 *******************************************************************************/
package org.eclipse.ice.viz.service.paraview.widgets;

import java.io.ByteArrayInputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.xml.bind.DatatypeConverter;

import org.eclipse.ice.viz.service.paraview.web.IParaViewWebClient;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * This class provides an SWT Canvas that can render images passed through via
 * an {@link IParaViewWebClient}.
 * 
 * @author Jordan Deyton
 *
 */
public class ParaViewCanvas extends Canvas implements PaintListener,
		ControlListener {

	/**
	 * The client used to render meshes remotely. It sends images back that will
	 * be painted onto this Canvas.
	 */
	private IParaViewWebClient client;

	/**
	 * The current view ID on the client.
	 */
	private int viewId = -1;

	/**
	 * The current image acquired from the {@link #client}.
	 */
	private final AtomicReference<Image> image = new AtomicReference<Image>();

	/**
	 * The service used to start worker threads.
	 */
	private final ExecutorService executorService;
	/**
	 * This lock is used by {@link #refreshRunnable} to determine if the refresh
	 * thread is currently running. In the unlikely--but possible--case that a
	 * second refresh thread gets started, this prevents the refresh threads
	 * from racing.
	 */
	private final Lock refreshLock = new ReentrantLock();
	/**
	 * If true, then the client needs to be queried and the Canvas updated. This
	 * is used to see if the {@link #refreshRunnable} needs to be started or if
	 * it should process another refresh event.
	 */
	private final AtomicBoolean stale = new AtomicBoolean();

	/**
	 * The quality of the rendered image. This is a parameter that is sent to
	 * the ParaView web client in
	 * {@link #refreshClient(IParaViewWebClient, int, int, int)}.
	 */
	private static final int imageQuality = 100;

	/**
	 * The default constructor.
	 * 
	 * @param parent
	 *            The parent Composite that will contain this Canvas.
	 * @param style
	 *            The style of the Canvas. By default, the Canvas has the
	 *            {@link SWT#DOUBLE_BUFFERED} style set.
	 */
	public ParaViewCanvas(Composite parent, int style) {
		super(parent, style | SWT.DOUBLE_BUFFERED);

		// Set up the ExecutorService so we can start threads later.
		executorService = Executors.newSingleThreadExecutor();

		// Register for paint and control resize events.
		addPaintListener(this);
		addControlListener(this);

		return;
	}

	/**
	 * Sets the current ParaView web client used by this Canvas.
	 * <p>
	 * <b>Note:</b> Any change is not guaranteed to take effect until the next
	 * refresh operation, which happens either after a manual call to
	 * {@link #refresh()} or after the Canvas has been resized.
	 * </p>
	 * 
	 * @param client
	 *            The new client. If {@code null} or not connected, then the
	 *            rendered image will not be able to update.
	 * @return True if the client was changed to a <i>new</i> value, false
	 *         otherwise.
	 */
	public boolean setClient(IParaViewWebClient client) {
		boolean changed = false;
		if (client != this.client) {
			this.client = client;
			changed = true;
		}
		return changed;
	}

	/**
	 * Sets the ID of the current view that is rendered via the associated
	 * ParaView web client.
	 * <p>
	 * <b>Note:</b> Any change is not guaranteed to take effect until the next
	 * refresh operation, which happens either after a manual call to
	 * {@link #refresh()} or after the Canvas has been resized.
	 * </p>
	 * 
	 * @param viewId
	 *            The ID of the view to be rendered. If invalid, then the
	 *            rendered image will not be able to update.
	 * @return True if the view ID was changed to a <i>new</i> value, false
	 *         otherwise.
	 */
	public boolean setViewId(int viewId) {
		boolean changed = false;
		if (viewId != this.viewId) {
			this.viewId = viewId;
			changed = true;
		}
		return changed;
	}

	/**
	 * Triggers a refresh of the Canvas. This method may be called from off the
	 * UI thread.
	 */
	public void refresh() {

		/**
		 * Since requesting a new image from the proxy may be time consuming,
		 * refreshing should trigger a separate thread that syncs with the UI
		 * only when necessary.
		 * 
		 * Below, we use the "stale" flag to determine if we need to create a
		 * new thread. The thread uses the "stale" flag in a while loop to
		 * continue processing refresh events.
		 * 
		 * There is a possible race condition where an existing refresh thread
		 * sets the "stale" flag to false. If this method is called immediately
		 * elsewhere, then this code will launch a new refresh thread. Then two
		 * refresh threads can potentially conflict. To get around this, we use
		 * the "refreshLock" to ensure that only one thread will be doing actual
		 * work at any given time.
		 */

		// Mark the stale flag. If it was unset, then we need to start a
		// new thread to refresh based on the current state of the client.
		if (!stale.getAndSet(true)) {
			executorService.submit(new Runnable() {
				@Override
				public void run() {

					// Before we start handling refresh events, we need to make
					// sure another refresh thread is not running. This blocks
					// until that thread finishes.
					refreshLock.lock();
					try {

						// Keep processing refresh events while the view is
						// stale.
						while (stale.getAndSet(false)) {

							// Get the current size of the Canvas.
							final Point size = new Point(0, 0);
							getDisplay().syncExec(new Runnable() {
								@Override
								public void run() {
									if (!isDisposed()) {
										Point canvasSize = getSize();
										size.x = canvasSize.x;
										size.y = canvasSize.y;
									}
								}
							});

							// TODO Remove the print messages.
							// Request a new Image from the client.
							System.out.println("Requesting image with size "
									+ size.x + " " + size.y);
							final Image newImage = refreshClient(client,
									viewId, size.x, size.y);
							System.out.println("Received image with size "
									+ newImage.getBounds().width + " "
									+ newImage.getBounds().height);

							// If a new Image could be retrieved, sync it with
							// the UI thread. Note: We don't need to wait on the
							// UI thread to handle this update.
							if (newImage != null) {
								getDisplay().asyncExec(new Runnable() {
									@Override
									public void run() {
										if (!isDisposed()) {
											image.set(newImage);
											redraw();
										}
									}
								});
							}
						}

					} finally {
						// Notify any pending refresh thread that this thread
						// has finished.
						refreshLock.unlock();
					}

					return;
				}
			});
		}

		return;
	}

	/*
	 * Implements a method from PaintListener.
	 */
	@Override
	public void paintControl(PaintEvent e) {
		// Paint the current image onto the Canvas.
		Image image = this.image.get();
		if (image != null) {
			Rectangle imgBounds = image.getBounds();
			e.gc.drawImage(image, 0, 0, imgBounds.width, imgBounds.height, 0,
					0, e.width, e.height);
		}
		return;
	}

	/*
	 * Implements a method from ControlListener.
	 */
	@Override
	public void controlMoved(ControlEvent e) {
		// Nothing to do.
	}

	/*
	 * Implements a method from ControlListener.
	 */
	@Override
	public void controlResized(ControlEvent e) {
		// Trigger an update to the client.
		refresh();
	}

	/**
	 * Sends an update request to the specified client. This operation waits for
	 * the response, after which it will construct an Image from the encoded
	 * image string. If the returned image is stale, then {@link #stale} is set
	 * to true.
	 * <p>
	 * <b>Note:</b> This operation is intended to be called from the refresh
	 * thread in {@link #refreshRunnable}.
	 * </p>
	 * 
	 * @param client
	 *            The client from which to request a new image.
	 * @param viewId
	 *            The ID of the view to render on the client.
	 * @param width
	 *            The width of the Canvas when making the request.
	 * @param height
	 *            The height of the Canvas when making the request.
	 * @return An Image from the client, or {@code null} if the render request
	 *         could not be completed.
	 */
	private Image refreshClient(IParaViewWebClient client, int viewId,
			int width, int height) {

		// Set the default return value.
		Image image = null;

		if (client != null && width > 0 && height > 0) {

			// The request to draw will return an object containing an encoded
			// image string and a flag stating whether the image is stale.

			// Send a render request to the client and wait for the reply.
			JsonObject response = null;
			try {
				response = client.render(viewId, imageQuality, width, height)
						.get();
			} catch (InterruptedException | ExecutionException e) {
				e.printStackTrace();
			}

			// If the response was received, try to read in the encoded image
			// and the stale flag.
			if (response != null) {

				// Read the base 64 image string from the response, then
				// construct a new Image from the encoded string.
				JsonElement element = response.get("image");
				if (element != null && element.isJsonPrimitive()) {
					try {
						String base64Image = element.getAsString();

						// TODO When we start using Java 8, replace the
						// DatatypeConverter with the java.util.Base64
						// class.
						byte[] decode = DatatypeConverter
								.parseBase64Binary(base64Image);
						// byte[] decode = Base64.getDecoder().decode(
						// base64Image.getBytes());
						ByteArrayInputStream inputStream = new ByteArrayInputStream(
								decode);

						// Load the input stream into a new Image.
						ImageData[] data = new ImageLoader().load(inputStream);
						if (data.length > 0) {
							image = new Image(getDisplay(), data[0]);
						}
					} catch (ClassCastException e) {
						// Could not read the image.
					}
				}

				// If the image is stale, trigger another refresh operation.
				element = response.get("stale");
				if (element != null && element.isJsonPrimitive()) {
					try {
						if (element.getAsBoolean()) {
							stale.set(true);
						}
					} catch (ClassCastException e) {
						// Could not read the stale variable.
					}
				}

			}
		}

		return image;
	}
}