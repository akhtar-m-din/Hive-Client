/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.cli;

import static org.apache.hadoop.util.StringUtils.stringifyException;

import java.io.*;
import java.sql.SQLException;
import java.util.*;

import com.google.common.base.Splitter;
import com.sherpa.core.utils.ConfigurationLoader;
import com.sherpa.tunecore.joblauncher.com.sherpa.tunecore.joblauncher.hivecli.HiveCliFactory;
import com.sherpa.tunecore.joblauncher.com.sherpa.tunecore.joblauncher.hivecli.HiveCliJobExecutor;
import jline.console.ConsoleReader;
import jline.console.completer.Completer;
import jline.console.history.FileHistory;
import jline.console.history.PersistentHistory;
import jline.console.completer.StringsCompleter;
import jline.console.completer.ArgumentCompleter;
import jline.console.completer.ArgumentCompleter.ArgumentDelimiter;
import jline.console.completer.ArgumentCompleter.AbstractArgumentDelimiter;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.HiveInterruptUtils;
import org.apache.hadoop.hive.common.LogUtils;
import org.apache.hadoop.hive.common.LogUtils.LogInitializationException;
import org.apache.hadoop.hive.common.cli.ShellCmdExecutor;
import org.apache.hadoop.hive.common.io.CachingPrintStream;
import org.apache.hadoop.hive.common.io.FetchConverter;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.Validator;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.ql.CommandNeedRetryException;
import org.apache.hadoop.hive.ql.Driver;
import org.apache.hadoop.hive.ql.exec.FunctionRegistry;
import org.apache.hadoop.hive.ql.exec.mr.HadoopJobExecHelper;
import org.apache.hadoop.hive.ql.exec.tez.TezJobExecHelper;
import org.apache.hadoop.hive.ql.parse.HiveParser;
import org.apache.hadoop.hive.ql.parse.VariableSubstitution;
import org.apache.hadoop.hive.ql.processors.CommandProcessor;
import org.apache.hadoop.hive.ql.processors.CommandProcessorFactory;
import org.apache.hadoop.hive.ql.processors.CommandProcessorResponse;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.hive.ql.session.SessionState.LogHelper;
import org.apache.hadoop.io.IOUtils;

import org.apache.zookeeper.ZooKeeper;
import sun.misc.Signal;
import sun.misc.SignalHandler;


/**
 * CliDriver.
 *
 */
public class CliDriver {

  public static String prompt = null;
  public static String prompt2 = null; // when ';' is not yet seen
  public static final int LINES_TO_FETCH = 40; // number of lines to fetch in batch from remote hive server
  public static final int DELIMITED_CANDIDATE_THRESHOLD = 10;

  public static final String HIVERCFILE = ".hiverc";

  private final LogHelper console;
  private Configuration conf;
  private HiveConf oldConf;
  private boolean usingSherpaProperties=false;

  public CliDriver() {
    SessionState ss = SessionState.get();
    conf = (ss != null) ? ss.getConf() : new Configuration();
    Log LOG = LogFactory.getLog("CliDriver");
    console = new LogHelper(LOG);
  }



  public void printMap(Map<String, String> map){
    console.printInfo("\n\n *** Printing Hive Variables");
    Iterator<String> it = map.keySet().iterator();
    while(it.hasNext()){
      String key = it.next();
      if(key.equalsIgnoreCase("PSManaged")) {
        console.printInfo("Key: " + key + "\t Value: " + map.get(key));
      }
    }
  }


  public HiveConf defineHiveConf(HiveConf conf){
    //conf.set("hive.exec.reducers.max", "10");
    conf.setInt("hive.exec.reducers.max", 10);

    return conf;
  }


  public void loadParameters(HiveConf conf){

    if(!conf.getChangedProperties().containsKey("PSManaged")) {
      console.printInfo("\n ****** Using Default Parameters");
      return;
    }


    String val = (String)conf.getChangedProperties().get("PSManaged");
    boolean psManaged = false;
    if(val!=null && val.equalsIgnoreCase("true"))
      psManaged = true;

    console.printInfo("PSManaged=" + psManaged);

    //loads Sherpa's Parameters
    if(psManaged){

      // Make sure, we dont load parameters for every command typed
      // loads parameters only once at the start
      if(!usingSherpaProperties){
        console.printInfo("\n******* Loading Sherpa's Parameters");
        oldConf = new HiveConf();
        console.printInfo("Copying existing parameters ...");
        copyConf(conf, oldConf);
        Properties prop = loadProperties();
        console.printInfo("Overriding parameters: " + prop);
        Iterator<String> iterator = prop.stringPropertyNames().iterator();
        while(iterator.hasNext()){
            String pName = iterator.next();
            conf.set(pName, prop.getProperty(pName));
        }
        console.printInfo("Done overriding parameters: ");
        usingSherpaProperties = true;
      }

    }
    else{
      if(usingSherpaProperties){
        console.printInfo("\n******* Restoring original parameters");
        copyConf(oldConf, conf);
        console.printInfo("\n******* Restored original parameters");
        usingSherpaProperties = false;
      }
      else
        console.printInfo("\n **** Using Default Properties");

    }


  }


  public void copyConf(HiveConf src, HiveConf dest){
    for(Map.Entry<Object, Object> c: src.getChangedProperties().entrySet()){
      dest.set((String)c.getKey(), (String)c.getValue() );
    }
  }


  public Properties loadProperties(){
    Properties properties = new Properties();
    String filePath = "/root/sherpa.properties";
    try{
        properties.load(new FileInputStream(filePath));
        console.printInfo("\n ***** Sherpa Properties Loaded: " + properties.toString() + " \n");
    }catch (IOException e){
      console.printInfo("\n\n Error: Properties file not found at " + filePath);
    }

    return properties;
  }





  public int processCmd(String cmd) {
    CliSessionState ss = (CliSessionState) SessionState.get();
    ss.setLastCommand(cmd);

    loadParameters(ss.getConf());

    // Flush the print stream, so it doesn't include output from the last command
    ss.err.flush();
    String cmd_trimmed = cmd.trim();
    String[] tokens = tokenizeCmd(cmd_trimmed);
    int ret = 0;

    if (cmd_trimmed.toLowerCase().equals("quit") || cmd_trimmed.toLowerCase().equals("exit")) {

      // if we have come this far - either the previous commands
      // are all successful or this is command line. in either case
      // this counts as a successful run
      ss.close();
      System.exit(0);

    } else if (tokens[0].equalsIgnoreCase("source")) {
      String cmd_1 = getFirstCmd(cmd_trimmed, tokens[0].length());
      cmd_1 = new VariableSubstitution().substitute(ss.getConf(), cmd_1);

      File sourceFile = new File(cmd_1);
      if (! sourceFile.isFile()){
        console.printError("File: "+ cmd_1 + " is not a file.");
        ret = 1;
      } else {
        try {
          ret = processFile(cmd_1);
        } catch (IOException e) {
          console.printError("Failed processing file "+ cmd_1 +" "+ e.getLocalizedMessage(),
            stringifyException(e));
          ret = 1;
        }
      }
    } else if (cmd_trimmed.startsWith("!")) {

      String shell_cmd = cmd_trimmed.substring(1);
      shell_cmd = new VariableSubstitution().substitute(ss.getConf(), shell_cmd);

      // shell_cmd = "/bin/bash -c \'" + shell_cmd + "\'";
      try {
        ShellCmdExecutor executor = new ShellCmdExecutor(shell_cmd, ss.out, ss.err);
        ret = executor.execute();
        if (ret != 0) {
          console.printError("Command failed with exit code = " + ret);
        }
      } catch (Exception e) {
        console.printError("Exception raised from Shell command " + e.getLocalizedMessage(),
            stringifyException(e));
        ret = 1;
      }
    }  else { // local mode
      try {
        CommandProcessor proc = CommandProcessorFactory.get(tokens, (HiveConf) conf);
        ret = processLocalCmd(cmd, proc, ss);
      } catch (SQLException e) {
        console.printError("Failed processing command " + tokens[0] + " " + e.getLocalizedMessage(),
          org.apache.hadoop.util.StringUtils.stringifyException(e));
        ret = 1;
      }
    }

    return ret;
  }

  /**
   * For testing purposes to inject Configuration dependency
   * @param conf to replace default
   */
  void setConf(Configuration conf) {
    this.conf = conf;
  }

  /**
   * Extract and clean up the first command in the input.
   */
  private String getFirstCmd(String cmd, int length) {
    return cmd.substring(length).trim();
  }

  private String[] tokenizeCmd(String cmd) {
    return cmd.split("\\s+");
  }

  int processLocalCmd(String cmd, CommandProcessor proc, CliSessionState ss) {
    int tryCount = 0;
    boolean needRetry;
    int ret = 0;

    do {
      try {
        needRetry = false;
        if (proc != null) {
          if (proc instanceof Driver) {
            Driver qp = (Driver) proc;
            PrintStream out = ss.out;
            long start = System.currentTimeMillis();
            if (ss.getIsVerbose()) {
              out.println(cmd);
            }

            qp.setTryCount(tryCount);
            ret = qp.run(cmd).getResponseCode();
            if (ret != 0) {
              qp.close();
              return ret;
            }

            // query has run capture the time
            long end = System.currentTimeMillis();
            double timeTaken = (end - start) / 1000.0;

            ArrayList<String> res = new ArrayList<String>();

            printHeader(qp, out);

            // print the results
            int counter = 0;
            try {
              if (out instanceof FetchConverter) {
                ((FetchConverter)out).fetchStarted();
              }
              while (qp.getResults(res)) {
                for (String r : res) {
                  out.println(r);
                }
                counter += res.size();
                res.clear();
                if (out.checkError()) {
                  break;
                }
              }
            } catch (IOException e) {
              console.printError("Failed with exception " + e.getClass().getName() + ":"
                  + e.getMessage(), "\n"
                  + org.apache.hadoop.util.StringUtils.stringifyException(e));
              ret = 1;
            }

            int cret = qp.close();
            if (ret == 0) {
              ret = cret;
            }

            if (out instanceof FetchConverter) {
              ((FetchConverter)out).fetchFinished();
            }

            console.printInfo("Time taken: " + timeTaken + " seconds" +
                (counter == 0 ? "" : ", Fetched: " + counter + " row(s)"));
          } else {
            String firstToken = tokenizeCmd(cmd.trim())[0];
            String cmd_1 = getFirstCmd(cmd.trim(), firstToken.length());

            if (ss.getIsVerbose()) {
              ss.out.println(firstToken + " " + cmd_1);
            }
            CommandProcessorResponse res = proc.run(cmd_1);
            if (res.getResponseCode() != 0) {
              ss.out.println("Query returned non-zero code: " + res.getResponseCode() +
                  ", cause: " + res.getErrorMessage());
            }
            ret = res.getResponseCode();
          }
        }
      } catch (CommandNeedRetryException e) {
        console.printInfo("Retry query with a different approach...");
        tryCount++;
        needRetry = true;
      }
    } while (needRetry);

    return ret;
  }

  /**
   * If enabled and applicable to this command, print the field headers
   * for the output.
   *
   * @param qp Driver that executed the command
   * @param out PrintStream which to send output to
   */
  private void printHeader(Driver qp, PrintStream out) {
    List<FieldSchema> fieldSchemas = qp.getSchema().getFieldSchemas();
    if (HiveConf.getBoolVar(conf, HiveConf.ConfVars.HIVE_CLI_PRINT_HEADER)
          && fieldSchemas != null) {
      // Print the column names
      boolean first_col = true;
      for (FieldSchema fs : fieldSchemas) {
        if (!first_col) {
          out.print('\t');
        }
        out.print(fs.getName());
        first_col = false;
      }
      out.println();
    }
  }

  public int processLine(String line) {
    return processLine(line, false);
  }

  /**
   * Processes a line of semicolon separated commands
   *
   * @param line
   *          The commands to process
   * @param allowInterrupting
   *          When true the function will handle SIG_INT (Ctrl+C) by interrupting the processing and
   *          returning -1
   * @return 0 if ok
   */
  public int processLine(String line, boolean allowInterrupting) {
    SignalHandler oldSignal = null;
    Signal interruptSignal = null;

    if (allowInterrupting) {
      // Remember all threads that were running at the time we started line processing.
      // Hook up the custom Ctrl+C handler while processing this line
      interruptSignal = new Signal("INT");
      oldSignal = Signal.handle(interruptSignal, new SignalHandler() {
        private final Thread cliThread = Thread.currentThread();
        private boolean interruptRequested;

        @Override
        public void handle(Signal signal) {
          boolean initialRequest = !interruptRequested;
          interruptRequested = true;

          // Kill the VM on second ctrl+c
          if (!initialRequest) {
            console.printInfo("Exiting the JVM");
            System.exit(127);
          }

          // Interrupt the CLI thread to stop the current statement and return
          // to prompt
          console.printInfo("Interrupting... Be patient, this might take some time.");
          console.printInfo("Press Ctrl+C again to kill JVM");

          // First, kill any running MR jobs
          HadoopJobExecHelper.killRunningJobs();
          TezJobExecHelper.killRunningJobs();
          HiveInterruptUtils.interrupt();
        }
      });
    }

    try {
      int lastRet = 0, ret = 0;

      String command = "";
      for (String oneCmd : line.split(";")) {

        if (StringUtils.endsWith(oneCmd, "\\")) {
          command += StringUtils.chop(oneCmd) + ";";
          continue;
        } else {
          command += oneCmd;
        }
        if (StringUtils.isBlank(command)) {
          continue;
        }

        ret = processCmd(command);
        //wipe cli query state
        SessionState ss = SessionState.get();
        ss.setCommandType(null);
        command = "";
        lastRet = ret;
        boolean ignoreErrors = HiveConf.getBoolVar(conf, HiveConf.ConfVars.CLIIGNOREERRORS);
        if (ret != 0 && !ignoreErrors) {
          CommandProcessorFactory.clean((HiveConf) conf);
          return ret;
        }
      }
      CommandProcessorFactory.clean((HiveConf) conf);
      return lastRet;
    } finally {
      // Once we are done processing the line, restore the old handler
      if (oldSignal != null && interruptSignal != null) {
        Signal.handle(interruptSignal, oldSignal);
      }
    }
  }

  public int processReader(BufferedReader r) throws IOException {
    String line;
    StringBuilder qsb = new StringBuilder();

    while ((line = r.readLine()) != null) {
      // Skipping through comments
      if (! line.startsWith("--")) {
        qsb.append(line + "\n");
      }
    }

    String appServer = ConfigurationLoader.getApplicationServerUrl();
    String jobHistoryServer = ConfigurationLoader.getJobHistoryUrl();
    String zooKeeper = ConfigurationLoader.getZookeeper();
    int pollInterval = ConfigurationLoader.getPollInterval();

    //System.out.println("\n\n\n ***** ZooKeeper: " + zooKeeper);

    HiveCliJobExecutor jobExecutor = HiveCliFactory.getHiveCliJobExecutorInstance(qsb.toString(), appServer, jobHistoryServer, pollInterval);
    jobExecutor.setName("HiveCliJobExecutor");
    jobExecutor.start();

    int ret = processLine(qsb.toString());

    try {
      jobExecutor.join();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }


    return ret;
  }

  public int processFile(String fileName) throws IOException {
    Path path = new Path(fileName);
    FileSystem fs;
    if (!path.toUri().isAbsolute()) {
      fs = FileSystem.getLocal(conf);
      path = fs.makeQualified(path);
    } else {
      fs = FileSystem.get(path.toUri(), conf);
    }
    BufferedReader bufferReader = null;
    int rc = 0;
    try {
      bufferReader = new BufferedReader(new InputStreamReader(fs.open(path)));
      rc = processReader(bufferReader);
    } finally {
      IOUtils.closeStream(bufferReader);
    }
    return rc;
  }

  public void processInitFiles(CliSessionState ss) throws IOException {
    boolean saveSilent = ss.getIsSilent();
    ss.setIsSilent(true);
    for (String initFile : ss.initFiles) {
      int rc = processFile(initFile);
      if (rc != 0) {
        System.exit(rc);
      }
    }
    if (ss.initFiles.size() == 0) {
      if (System.getenv("HIVE_HOME") != null) {
        String hivercDefault = System.getenv("HIVE_HOME") + File.separator +
          "bin" + File.separator + HIVERCFILE;
        if (new File(hivercDefault).exists()) {
          int rc = processFile(hivercDefault);
          if (rc != 0) {
            System.exit(rc);
          }
          console.printError("Putting the global hiverc in " +
                             "$HIVE_HOME/bin/.hiverc is deprecated. Please "+
                             "use $HIVE_CONF_DIR/.hiverc instead.");
        }
      }
      if (System.getenv("HIVE_CONF_DIR") != null) {
        String hivercDefault = System.getenv("HIVE_CONF_DIR") + File.separator
          + HIVERCFILE;
        if (new File(hivercDefault).exists()) {
          int rc = processFile(hivercDefault);
          if (rc != 0) {
            System.exit(rc);
          }
        }
      }
      if (System.getProperty("user.home") != null) {
        String hivercUser = System.getProperty("user.home") + File.separator +
          HIVERCFILE;
        if (new File(hivercUser).exists()) {
          int rc = processFile(hivercUser);
          if (rc != 0) {
            System.exit(rc);
          }
        }
      }
    }
    ss.setIsSilent(saveSilent);
  }

  public void processSelectDatabase(CliSessionState ss) throws IOException {
    String database = ss.database;
    if (database != null) {
      int rc = processLine("use " + database + ";");
      if (rc != 0) {
        System.exit(rc);
      }
    }
  }

  public static Completer[] getCommandCompleter() {
    // StringsCompleter matches against a pre-defined wordlist
    // We start with an empty wordlist and build it up
    List<String> candidateStrings = new ArrayList<String>();

    // We add Hive function names
    // For functions that aren't infix operators, we add an open
    // parenthesis at the end.
    for (String s : FunctionRegistry.getFunctionNames()) {
      if (s.matches("[a-z_]+")) {
        candidateStrings.add(s + "(");
      } else {
        candidateStrings.add(s);
      }
    }

    // We add Hive keywords, including lower-cased versions
    for (String s : HiveParser.getKeywords()) {
      candidateStrings.add(s);
      candidateStrings.add(s.toLowerCase());
    }

    StringsCompleter strCompleter = new StringsCompleter(candidateStrings);

    // Because we use parentheses in addition to whitespace
    // as a keyword delimiter, we need to define a new ArgumentDelimiter
    // that recognizes parenthesis as a delimiter.
    ArgumentDelimiter delim = new AbstractArgumentDelimiter() {
      @Override
      public boolean isDelimiterChar(CharSequence buffer, int pos) {
        char c = buffer.charAt(pos);
        return (Character.isWhitespace(c) || c == '(' || c == ')' ||
            c == '[' || c == ']');
      }
    };

    // The ArgumentCompletor allows us to match multiple tokens
    // in the same line.
    final ArgumentCompleter argCompleter = new ArgumentCompleter(delim, strCompleter);
    // By default ArgumentCompletor is in "strict" mode meaning
    // a token is only auto-completed if all prior tokens
    // match. We don't want that since there are valid tokens
    // that are not in our wordlist (eg. table and column names)
    argCompleter.setStrict(false);

    // ArgumentCompletor always adds a space after a matched token.
    // This is undesirable for function names because a space after
    // the opening parenthesis is unnecessary (and uncommon) in Hive.
    // We stack a custom Completor on top of our ArgumentCompletor
    // to reverse this.
    Completer customCompletor = new Completer () {
      @Override
      public int complete (String buffer, int offset, List completions) {
        List<String> comp = completions;
        int ret = argCompleter.complete(buffer, offset, completions);
        // ConsoleReader will do the substitution if and only if there
        // is exactly one valid completion, so we ignore other cases.
        if (completions.size() == 1) {
          if (comp.get(0).endsWith("( ")) {
            comp.set(0, comp.get(0).trim());
          }
        }
        return ret;
      }
    };

    List<String> vars = new ArrayList<String>();
    for (HiveConf.ConfVars conf : HiveConf.ConfVars.values()) {
      vars.add(conf.varname);
    }

    StringsCompleter confCompleter = new StringsCompleter(vars) {
      @Override
      public int complete(final String buffer, final int cursor, final List<CharSequence> clist) {
        int result = super.complete(buffer, cursor, clist);
        if (clist.isEmpty() && cursor > 1 && buffer.charAt(cursor - 1) == '=') {
          HiveConf.ConfVars var = HiveConf.getConfVars(buffer.substring(0, cursor - 1));
          if (var == null) {
            return result;
          }
          if (var.getValidator() instanceof Validator.StringSet) {
            Validator.StringSet validator = (Validator.StringSet)var.getValidator();
            clist.addAll(validator.getExpected());
          } else if (var.getValidator() != null) {
            clist.addAll(Arrays.asList(var.getValidator().toDescription(), ""));
          } else {
            clist.addAll(Arrays.asList("Expects " + var.typeString() + " type value", ""));
          }
          return cursor;
        }
        if (clist.size() > DELIMITED_CANDIDATE_THRESHOLD) {
          Set<CharSequence> delimited = new LinkedHashSet<CharSequence>();
          for (CharSequence candidate : clist) {
            Iterator<String> it = Splitter.on(".").split(
                candidate.subSequence(cursor, candidate.length())).iterator();
            if (it.hasNext()) {
              String next = it.next();
              if (next.isEmpty()) {
                next = ".";
              }
              candidate = buffer != null ? buffer.substring(0, cursor) + next : next;
            }
            delimited.add(candidate);
          }
          clist.clear();
          clist.addAll(delimited);
        }
        return result;
      }
    };

    StringsCompleter setCompleter = new StringsCompleter("set") {
      @Override
      public int complete(String buffer, int cursor, List<CharSequence> clist) {
        return buffer != null && buffer.equals("set") ? super.complete(buffer, cursor, clist) : -1;
      }
    };

    ArgumentCompleter propCompleter = new ArgumentCompleter(setCompleter, confCompleter) {
      @Override
      public int complete(String buffer, int offset, List<CharSequence> completions) {
        int ret = super.complete(buffer, offset, completions);
        if (completions.size() == 1) {
          completions.set(0, ((String)completions.get(0)).trim());
        }
        return ret;
      }
    };
    return new Completer[] {propCompleter, customCompletor};
  }

  public static void main(String[] args) throws Exception {
    System.out.println("\n\n\n **************************** Hive CLI From Sherpa Performance ***************************** \n\n\n");

    int ret = new CliDriver().run(args);
    System.exit(ret);
  }

  public  int run(String[] args) throws Exception {

    OptionsProcessor oproc = new OptionsProcessor();
    if (!oproc.process_stage1(args)) {
      return 1;
    }

    // NOTE: It is critical to do this here so that log4j is reinitialized
    // before any of the other core hive classes are loaded
    boolean logInitFailed = false;
    String logInitDetailMessage;
    try {
      logInitDetailMessage = LogUtils.initHiveLog4j();
    } catch (LogInitializationException e) {
      logInitFailed = true;
      logInitDetailMessage = e.getMessage();
    }

    CliSessionState ss = new CliSessionState(new HiveConf(SessionState.class));
    ss.in = System.in;
    try {
      ss.out = new PrintStream(System.out, true, "UTF-8");
      ss.info = new PrintStream(System.err, true, "UTF-8");
      ss.err = new CachingPrintStream(System.err, true, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      return 3;
    }

    if (!oproc.process_stage2(ss)) {
      return 2;
    }

    if (!ss.getIsSilent()) {
      if (logInitFailed) {
        System.err.println(logInitDetailMessage);
      } else {
        SessionState.getConsole().printInfo(logInitDetailMessage);
      }
    }

    // set all properties specified via command line
    HiveConf conf = ss.getConf();
    for (Map.Entry<Object, Object> item : ss.cmdProperties.entrySet()) {
      conf.set((String) item.getKey(), (String) item.getValue());
      ss.getOverriddenConfigurations().put((String) item.getKey(), (String) item.getValue());
    }

    // read prompt configuration and substitute variables.
    prompt = conf.getVar(HiveConf.ConfVars.CLIPROMPT);
    prompt = new VariableSubstitution().substitute(conf, prompt);
    prompt2 = spacesForString(prompt);

    SessionState.start(ss);

    // execute cli driver work
    try {
      return executeDriver(ss, conf, oproc);
    } finally {
      ss.close();
    }
  }

  /**
   * Execute the cli work
   * @param ss CliSessionState of the CLI driver
   * @param conf HiveConf for the driver session
   * @param oproc Operation processor of the CLI invocation
   * @return status of the CLI command execution
   * @throws Exception
   */
  private int executeDriver(CliSessionState ss, HiveConf conf, OptionsProcessor oproc)
      throws Exception {

    CliDriver cli = new CliDriver();
    cli.setHiveVariables(oproc.getHiveVariables());

    // use the specified database if specified
    cli.processSelectDatabase(ss);

    // Execute -i init files (always in silent mode)
    cli.processInitFiles(ss);

    if (ss.execString != null) {
      int cmdProcessStatus = cli.processLine(ss.execString);
      return cmdProcessStatus;
    }

    try {
      if (ss.fileName != null) {
        return cli.processFile(ss.fileName);
      }
    } catch (FileNotFoundException e) {
      System.err.println("Could not open input file for reading. (" + e.getMessage() + ")");
      return 3;
    }

    ConsoleReader reader =  getConsoleReader();
    reader.setBellEnabled(false);
    // reader.setDebug(new PrintWriter(new FileWriter("writer.debug", true)));
    for (Completer completer : getCommandCompleter()) {
      reader.addCompleter(completer);
    }

    String line;
    final String HISTORYFILE = ".hivehistory";
    String historyDirectory = System.getProperty("user.home");
    PersistentHistory history = null;
    try {
      if ((new File(historyDirectory)).exists()) {
        String historyFile = historyDirectory + File.separator + HISTORYFILE;
        history = new FileHistory(new File(historyFile));
        reader.setHistory(history);
      } else {
        System.err.println("WARNING: Directory for Hive history file: " + historyDirectory +
                           " does not exist.   History will not be available during this session.");
      }
    } catch (Exception e) {
      System.err.println("WARNING: Encountered an error while trying to initialize Hive's " +
                         "history file.  History will not be available during this session.");
      System.err.println(e.getMessage());
    }

    int ret = 0;

    String prefix = "";
    String curDB = getFormattedDb(conf, ss);
    String curPrompt = prompt + curDB;
    String dbSpaces = spacesForString(curDB);

    while ((line = reader.readLine(curPrompt + "> ")) != null) {
      if (!prefix.equals("")) {
        prefix += '\n';
      }
      if (line.trim().endsWith(";") && !line.trim().endsWith("\\;")) {
        line = prefix + line;
        ret = cli.processLine(line, true);
        prefix = "";
        curDB = getFormattedDb(conf, ss);
        curPrompt = prompt + curDB;
        dbSpaces = dbSpaces.length() == curDB.length() ? dbSpaces : spacesForString(curDB);
      } else {
        prefix = prefix + line;
        curPrompt = prompt2 + dbSpaces;
        continue;
      }
    }

    if (history != null) {
      history.flush();
    }
    return ret;
  }

  protected ConsoleReader getConsoleReader() throws IOException{
    return new ConsoleReader();
  }
  /**
   * Retrieve the current database name string to display, based on the
   * configuration value.
   * @param conf storing whether or not to show current db
   * @param ss CliSessionState to query for db name
   * @return String to show user for current db value
   */
  private static String getFormattedDb(HiveConf conf, CliSessionState ss) {
    if (!HiveConf.getBoolVar(conf, HiveConf.ConfVars.CLIPRINTCURRENTDB)) {
      return "";
    }
    //BUG: This will not work in remote mode - HIVE-5153
    String currDb = SessionState.get().getCurrentDatabase();

    if (currDb == null) {
      return "";
    }

    return " (" + currDb + ")";
  }

  /**
   * Generate a string of whitespace the same length as the parameter
   *
   * @param s String for which to generate equivalent whitespace
   * @return  Whitespace
   */
  private static String spacesForString(String s) {
    if (s == null || s.length() == 0) {
      return "";
    }
    return String.format("%1$-" + s.length() +"s", "");
  }

  public void setHiveVariables(Map<String, String> hiveVariables) {
    SessionState.get().setHiveVariables(hiveVariables);
  }

}
