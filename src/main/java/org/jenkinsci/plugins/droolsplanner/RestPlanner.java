/*
 * The MIT License
 *
 * Copyright (c) 2012 Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.droolsplanner;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ws.rs.core.MediaType;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;

/**
 * Planner implementation that work as a remote proxy for REST API planner
 *
 * @author ogondza
 */
public final class RestPlanner implements Planner {

    private final static Logger LOGGER = Logger.getLogger(
            RestPlanner.class.getName()
    );

    private static final String PREFIX = "/rest/hudsonQueue";
    private static final String TYPE = MediaType.APPLICATION_JSON;

    private static final JsonSerializer serializator = new JsonSerializer();
    private static final Client client = Client.create();

    private enum Status {
        NOT_STARTED, RUNNING, STOPPED;

        public boolean isRunning() {

            return this == RUNNING;
        }

        public boolean isStopped() {

            return this == STOPPED;
        }
    }

    private final URL serviceDestination;
    private final String plannerName;
    private Status status = Status.NOT_STARTED;

    public RestPlanner(final URL serviceDestination) {

        if (serviceDestination == null) throw new IllegalArgumentException (
                "No URL provided"
        );

        this.serviceDestination = serviceDestination;
        this.plannerName = fetchPlannerName();
    }

    /**
     * @see org.jenkinsci.plugins.droolsplanner.Planner#remoteUrl()
     */
    public URL remoteUrl() {

        return serviceDestination;
    }

    public String name() {

        return plannerName;
    }

    /**
     * Validate URL
     * @return Application name or null when not a Drools planner
     */
    private String fetchPlannerName() {

        final String info = infoContent();
        final Matcher matcher = Pattern
                .compile("^hudson-queue-planning : (.*?) on URL")
                .matcher(info)
        ;

        final boolean valid = matcher.find();

        if (!valid) throw new PlannerException("Not a drools planner: " + info);

        return matcher.group();

    }

    private String infoContent() {

        return get(getResource("/info").accept(MediaType.TEXT_PLAIN));
    }

    /**
     * @see org.jenkinsci.plugins.droolsplanner.Planner#score()
     */
    public int score() {

        assumeRunning();

        info("Getting score");
        return serializator.extractScore(
                get(getResource("/score").accept(TYPE))
        );
    }

    /**
     * @see org.jenkinsci.plugins.droolsplanner.Planner#solution()
     */
    public NodeAssignments solution() {

        assumeRunning();

        info("Getting solution");
        return serializator.extractAssignments(
                get(getResource().accept(TYPE))
        );
    }

    private String get(final WebResource.Builder builder) {

        try {

            return builder.get(String.class);
        } catch (UniformInterfaceException ex) {

            throw new PlannerException(ex);
        } catch (ClientHandlerException ex) {

            throw new PlannerException(ex);
        }
    }

    /**
     * @see org.jenkinsci.plugins.droolsplanner.Planner#queue(org.jenkinsci.plugins.droolsplanner.StateProvider, org.jenkinsci.plugins.droolsplanner.NodeAssignments)
     */
    public RestPlanner queue(final StateProvider stateProvider, final NodeAssignments assignments) {

        assumeNotStopped();

        if (assignments == null) throw new IllegalArgumentException("No assignments");
        if (stateProvider == null) throw new IllegalArgumentException("No stateProvider");

        final WebResource.Builder resource = getResource().type(TYPE);

        final String queueString = serializator.buildQuery(stateProvider, assignments);

        try {
            if (status.isRunning()) {

                info("Sending queue update");
                resource.put(queueString);
            } else {

                info("Starting remote planner");
                resource.post(queueString);
                status = Status.RUNNING;
            }
        } catch (UniformInterfaceException ex) {

            throw new PlannerException(ex);
        } catch (ClientHandlerException ex) {

            throw new PlannerException(ex);
        }

        return this;
    }

    /**
     * @see org.jenkinsci.plugins.droolsplanner.Planner#stop()
     */
    public Planner stop() {

        if (status.isStopped()) {

            error(String.format(
                    "Planner %s already stopped", serviceDestination.toString()
            ));
            return this;
        }

        if (status.isRunning()) {

            info("Stopping remote planner");
        }

        status = Status.STOPPED;
        try {

            getResource().delete();
        } catch (UniformInterfaceException ex) {

            throw new PlannerException(ex);
        } catch (ClientHandlerException ex) {

            throw new PlannerException(ex);
        }

        return this;
    }

    private WebResource getResource() {

        return getResource("");
    }

    private WebResource getResource(final String suffix) {

        final URL url = getUrl(suffix);

        return client.resource(url.toString());
    }

    private URL getUrl(final String url) {

        try {

            return new URL(serviceDestination, PREFIX + url);
        } catch (MalformedURLException ex) {

            throw new AssertionError(
                    serviceDestination.toString() + PREFIX + url + ": " + ex.getMessage()
            );
        }
    }

    private void assumeRunning() {

        if (!status.isRunning()) throw new IllegalStateException(
                "Remote planner not running"
        );
    }

    private void assumeNotStopped() {

        if (status.isStopped()) throw new IllegalStateException(
                "Remote planner already stopped"
        );
    }

    private void info(final String message) {

        LOGGER.info(message);
    }

    private void error(final String message) {

        LOGGER.severe(message);
    }

    /**
     * Validate postconditions
     */
    protected void finalize() throws Throwable {

        if (!status.isRunning()) return;

        error(String.format(
                "Planner %s was not stopped properlly", serviceDestination.toString()
        ));

        stop();
    }
}
