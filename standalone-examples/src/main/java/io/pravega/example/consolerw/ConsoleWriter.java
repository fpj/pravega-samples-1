/**
 *
 *  Copyright (c) 2017 Dell Inc., or its subsidiaries.
 *
 */
package io.pravega.example.consolerw;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import io.pravega.ClientFactory;
import io.pravega.StreamManager;
import io.pravega.stream.AckFuture;
import io.pravega.stream.EventStreamWriter;
import io.pravega.stream.EventWriterConfig;
import io.pravega.stream.ScalingPolicy;
import io.pravega.stream.StreamConfiguration;
import io.pravega.stream.Transaction;
import io.pravega.stream.Transaction.Status;
import io.pravega.stream.TxnFailedException;
import io.pravega.stream.impl.JavaSerializer;

/**
 * Uses the Console to present a CLI to write events to a Stream or a Transaction.
 * Provides commands to begin, abort, commit, get_id, ping and get status for a Transaction.
 */

public class ConsoleWriter implements AutoCloseable {
    private static final long DEFAULT_TXN_TIMEOUT_MS = 30000L;
    private static final long DEFAULT_TXN_MAX_EXECUTION_TIME_MS = 30000L;
    private static final long DEFAULT_TXN_SCALE_GRACE_PERIOD_MS = 30000L;
    
    private static final long DEFAULT_PING_LEASE_MS = 30000L;
    
    private static final String[] HELP_TEXT = {
            "Enter one of the following commands at the command line prompt:",
            "",
            "If no command is entered, the line is treated as a parameter to the WRITE_EVENT command.",
            "",
            "WRITE_EVENT {event} - write the {event} out to the Stream or the current Transaction.",
            "WRITE_EVENT_RK <<{routingKey}>> , {event} - write the {event} out to the Stream or the current Transaction using {routingKey}. Note << and >> around {routingKey}.",
            "BEGIN [{transactionTimeout}] [, {maxExecutionTime}] [, {scaleGracePeriod}] begin a Transaction. Only one Transaction at a time is supported by the CLI.",
            "GET_TXN_ID - output the current Transaction's Id (if a Transaction is running)",
            "FLUSH - flush the current Transaction (if a Transaction is running)",
            "PING [{lease}] - refresh the time remaining on the Transaction (if a Transaction is running)",
            "COMMIT - commit the Transaction (if a Transaction is running)",
            "ABORT - abort the Transaction (if a Transaction is running)",
            "STATUS - check the status of the Transaction(if a Transaction is running)",
            "HELP - print out a list of commands.",
            "QUIT - terminate the program."
    };
    
    private final String scope;
    private final String streamName; 
    private final EventStreamWriter<String> writer;
    private Transaction<String> txn = null;
    
    
    public ConsoleWriter(String scope, String streamName, EventStreamWriter<String> writer) {
        this.scope = scope;
        this.streamName = streamName;
        this.writer = writer;
        
        this.txn = null;  //by default, the ConsoleWriter is not in TXN mode
        
    }

    /*
     * Use the console to accept commands from the command line and execute the commands against the stream
     */
    public void run() throws IOException{
        boolean done = false;
        
        outputHelp();
        
        while(!done){
            String commandLine = readLine("%s >", getPrompt()).trim();
            if (! commandLine.equals("")) {
                done = processCommand(commandLine);
            }
        }
    }
    
    private String getPrompt() {
        return txn != null ? txn.getTxnId().toString() : scope + "/" + streamName;
    }
    
    /*
     * Indirection to deal with Eclipse console bug #122429
     */
    private String readLine(String format, Object... args) throws IOException {
        if (System.console() != null) {
            return System.console().readLine(format, args);
        }
        System.out.print(String.format(format, args));
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                System.in));
        return reader.readLine();
        
    }
    
    /*
     * The raw format of the command is [COMMAND] [parm [,parm]]
     */
    private boolean processCommand(String rawString) {
        boolean ret = false;
        final Scanner sc = new Scanner(rawString);
        final String command = sc.next();
        List<String> parms;
        final String restOfLine;
        if (sc.hasNextLine()) {
            restOfLine = sc.nextLine();
            final String[] rawParms = restOfLine.split(",");
            parms = Arrays.asList(rawParms);
            parms.replaceAll(String::trim);
        } else {
            parms = new ArrayList<String>();
            restOfLine = null;
        }
        
        switch(command.toUpperCase()) {
            case "WRITE_EVENT":
                doWriteEvent(restOfLine);
                break;
            case "WRITE_EVENT_RK":
                doWriteEventRK(restOfLine);
                break;
            case "BEGIN":
                doBeginTxn(parms);
                break;
            case "GET_TXN_ID":
                doGetTxnId(parms);
                break;
            case "FLUSH":
                doFlushTxn(parms);
                break;
            case "PING":
                doPingTxn(restOfLine);
                break;
            case "COMMIT":
                doCommitTxn(parms);
                break;
            case "ABORT":
                doAbortTxn(parms);
                break;
            case "STATUS":
                doCheckTxnStatus(parms);
                break;
            case "HELP" :
                doHelp(parms);
                break;
            case "QUIT" :
                ret = true;
                output("Exiting...%n");
                break;
            default :
                doWriteEvent(rawString);
                break;
        }
        sc.close();
        return ret;
    }
    
    private void doWriteEvent(String event) {
        if (txn == null) {
            AckFuture future = writer.writeEvent(event);
            try {
                future.get();
                output("Wrote '%s'%n", event);
            } catch (Exception e) {
                warn("Write event failed.%n");
                output(e);
            }
        } else {
            try {
                txn.writeEvent(event);
                output("Wrote '%s'%n", event);
            } catch (TxnFailedException e) {
                warn("Write event to transaction failed.%n");
                output(e);
            }
        }
    }
    
    private void doWriteEventRK(String restOfLine) {
        final String routingKey;
        final String message;
        try(final Scanner sc = new Scanner(restOfLine.trim()).useDelimiter("(?<=[()])|(?=[()])")){
            if (sc.next().equals("(")) {
                routingKey = sc.next().trim();
                if (sc.next().equals(")")) {
                    message = sc.next().trim();
                    writeEventRK(routingKey, message);
                    return;
                }
            }
        } catch (Exception e ) {
            //ignore, it is a syntax error, the end of this method will emit the warning message
        }
        warn("Expecting '('routingkey')' message%n");
    }
    
    private void writeEventRK(String routingKey, String message) {
        if (txn == null) {
            AckFuture future = writer.writeEvent(routingKey, message);
            try {
                future.get();
                output("Wrote using routing key '%s' message '%s'%n", routingKey, message);
            } catch (Exception e) {
                warn("Write event failed.%n");
                output(e);
            }
        } else {
            try {
                txn.writeEvent(routingKey, message);
                output("Wrote using routing key '%s' message '%s'%n", routingKey, message);
            } catch (TxnFailedException e) {
                warn("Write event to transaction failed.%n");
                output(e);
            }
        }
    }
    
    private void doBeginTxn(List<String> parms) {
        String ignoredParms = null;
        long transactionTimeout = DEFAULT_TXN_TIMEOUT_MS;
        long maxExecutionTime = DEFAULT_TXN_MAX_EXECUTION_TIME_MS;
        long scaleGracePeriod = DEFAULT_TXN_SCALE_GRACE_PERIOD_MS;
        
        try {
            if (parms.size() > 0) {
                transactionTimeout = Long.parseLong(parms.get(0));
            }
            if (parms.size() > 1) {
                maxExecutionTime = Long.parseLong(parms.get(1));
            }
            if (parms.size() > 2) {
                scaleGracePeriod = Long.parseLong(parms.get(2));
            }
        } catch (Exception e ) {
            warn("Expecting numeric values as parameters.%n");
        }
        
        if (txn == null) {
            try {
                txn = writer.beginTxn(transactionTimeout, maxExecutionTime, scaleGracePeriod);
            } catch (Exception e) {
                warn("Failed to begin a new transaction.%n");
                output(e);
            }
        } else {
            warn("Cannot begin a new transaction -- commit or abort the current transaction.%n");
        }
        
        if (parms.size() > 3) {
            warn("Ignoring parameters: '%s'%n", String.join(",", parms.stream().skip(3).collect(Collectors.toList())));
        }
    }
    
    private void doGetTxnId(List<String> parms) {
        if (txn == null) {
            warn("Cannot get transaction id -- begin a transaction first.%n");
        } else {
            final UUID txn_id = txn.getTxnId();
            output("Transaction id: %s%n", txn_id);
        }
        
        if (parms.size() > 0) {
            warn("Ignoring parameters: '%s'%n", String.join(",", parms));
        }
    }
    
    private void doFlushTxn(List<String> parms) {
        if (txn == null) {
            warn("Cannot flush transaction -- begin a transaction first.%n");
        } else {
            try {
                txn.flush();
            } catch (TxnFailedException e) {
                warn("Transaction flush failed to complete.");
                output(e);
            }
            output("Transaction flush completed.%n");
        }
        
        if (parms.size() > 0) {
            warn("Ignoring parameters: '%s'%n", String.join(",", parms));
        }
    }
    
    private void doPingTxn(String restOfLine) {
        String ignoredParms = null;
        long lease = DEFAULT_PING_LEASE_MS;
        
        if (restOfLine != null) {
            try (final Scanner sc = new Scanner(restOfLine.trim()).useDelimiter(",")){
                if (sc.hasNextLong()) {
                    lease = sc.nextLong();
                }
                
                if (sc.hasNextLine()) {
                    ignoredParms = sc.nextLine();
                }
            }
        }
        
        if (txn == null ){
            warn("Cannot ping transaction -- begin a transaction first.%n");
        } else {
            try {
                txn.ping(lease);
                output("Transaction ping completed.%n");
            } catch (Exception e) {
                warn("Failed to ping transaction.%n");
                output(e);
            }
        }
        
        if (ignoredParms != null) {
            warn("Ignoring parameters: '%s'%n", ignoredParms);
        }

    }
    
    private void doCommitTxn(List<String> parms) {
        if (txn == null ){
            warn("Cannot commit transaction -- begin a transaction first.%n");
        } else {
            try {
                txn.commit();
                output("Transaction commit completed.%n");
            } catch (TxnFailedException e) {
                warn("Transaction commit failed.%n");
                output(e);
            }
            txn = null;
        }
        
        if (parms.size() > 0) {
            warn("Ignoring parameters: '%s'%n", String.join(",", parms));
        }
    }
    
    private void doAbortTxn(List<String> parms) {
        if (txn == null ){
            warn("Cannot abort transaction -- begin a transaction first.%n");
        } else {
            try {
                txn.abort();
                output("Transaction abort completed.%n");
                txn = null;
            } catch (Exception e) {
                warn("Transaction abort failed.%n");
                output(e);
            }
        }
        
        if (parms.size() > 0) {
            warn("Ignoring parameters: '%s'%n", String.join(",", parms));
        }
    }
    
    private void doCheckTxnStatus(List<String> parms) {
        if (txn == null ){
            warn("Cannot check transaction status -- begin a transaction first.%n");
        } else {
            try {
                Status status = txn.checkStatus();
                output("Transaction status: %s%n", status.toString());
            } catch (Exception e) {
                warn("Transaction check status failed.%n");
                output(e);
            }
        }
        
        if (parms.size() > 0) {
            warn("Ignoring parameters: '%s'%n", String.join(",", parms));
        }
    }

    private void doHelp(List<String> parms) {
        outputHelp();
        
        if (parms.size() > 0) {
            warn("Ignoring parameters: '%s'%n", String.join(",", parms));
        }
    }
    
    private void outputHelp() {
        Arrays.stream(HELP_TEXT).forEach(System.out::println);
        System.out.println("");
    }
    
    private void output(String format, Object... args){
        System.out.format("**** ");
        System.out.format(format, args);
    }
    
    private void warn(String format, Object... args){
        System.out.format("!!!! ");
        System.out.format(format, args);
    }
    
    private void output(Exception e) {
        e.printStackTrace();
        output("%n");
    }
    
    @Override
    public void close(){
        // TODO Auto-generated method stub
        
    }

    private static Options getOptions() {
        final Options options = new Options();
        options.addOption("s", "scope", true, "The scope (namespace) of the Stream to write to.");
        options.addOption("n", "name", true, "The name of the Stream to write to.");
        options.addOption("u", "uri", true, "The URI to the Pravega controller in the form tcp://host:port");
        return options;
    }

    private static CommandLine parseCommandLineArgs(Options options, String[] args) throws ParseException {
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);
        return cmd;
    }
    
    public static void main(String[] args) {
        Options options = getOptions();
        CommandLine cmd = null;
        try {
            cmd = parseCommandLineArgs(options, args);
        } catch (ParseException e) {
            e.printStackTrace();
            final HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("ConsoleWriter", options);
        }

        final String scope = cmd.getOptionValue("scope") == null ? Constants.DEFAULT_SCOPE : cmd.getOptionValue("scope");
        final String streamName = cmd.getOptionValue("name") == null ? Constants.DEFAULT_STREAM_NAME : cmd.getOptionValue("name");
        final String uriString = cmd.getOptionValue("uri") == null ? Constants.DEFAULT_CONTROLLER_URI : cmd.getOptionValue("uri");
        
        final URI controllerURI = URI.create(uriString);
        
        StreamManager streamManager = StreamManager.create(controllerURI);
        streamManager.createScope(scope);

        StreamConfiguration streamConfig = StreamConfiguration.builder().scope(scope).streamName(streamName)
                .scalingPolicy(ScalingPolicy.fixed(1))
                .build();

        streamManager.createStream(scope, streamName, streamConfig);
        
        try(
            ClientFactory clientFactory = ClientFactory.withScope(scope, controllerURI);
            EventStreamWriter<String> writer = clientFactory.createEventWriter(streamName,
                                                                                new JavaSerializer<String>(),
                                                                                EventWriterConfig.builder().build()); 
            ConsoleWriter cw = new ConsoleWriter(scope, streamName, writer);
           ){
            cw.run();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
