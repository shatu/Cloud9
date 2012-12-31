/*
 * Cloud9: A Hadoop toolkit for working with big data
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package edu.umd.cloud9.collection.trec;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Counters;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;

import edu.umd.cloud9.collection.DocnoMapping;

/**
 * Simple demo program that counts all the documents in the TREC collection. Run without any
 * arguments for help. Sample invocation:
 *
 * <pre>
 * hadoop jar dist/cloud9-X.X.X.jar edu.umd.cloud9.collection.trec.CountTrecDocuments \
 *  -libjars lib/guava-X.X.X.jar \
 *  -collection /shared/collections/trec/trec4-5_noCRFR.xml -output tmp \
 *  -docnoMapping trec-docno-mapping.dat -countOutput records.txt
 * </pre>
 *
 * @author Jimmy Lin
 */
public class CountTrecDocuments extends Configured implements Tool {
  private static final Logger LOG = Logger.getLogger(CountTrecDocuments.class);
  private static enum Count { DOCS };

  private static class MyMapper extends Mapper<LongWritable, TrecDocument, Text, IntWritable> {
    private static final Text docid = new Text();
    private static final IntWritable one = new IntWritable(1);
    private DocnoMapping docMapping;

    @Override
    public void setup(Context context) {
      try {
        Configuration conf = context.getConfiguration();
        Path[] localFiles = DistributedCache.getLocalCacheFiles(conf);

        // Instead of hard-coding the actual concrete DocnoMapping class, have the name of the
        // class passed in as a property; this makes the mapper more general.
        docMapping = (DocnoMapping) Class.forName(conf.get("DocnoMappingClass")).newInstance();

        // Simply assume that the mappings file is the only file in the distributed cache.
        docMapping.loadMapping(localFiles[0], FileSystem.getLocal(conf));
      } catch (Exception e) {
        e.printStackTrace();
        throw new RuntimeException("Error initializing DocnoMapping!");
      }
    }

    @Override
    public void map(LongWritable key, TrecDocument doc, Context context)
        throws IOException, InterruptedException {
      context.getCounter(Count.DOCS).increment(1);
      docid.set(doc.getDocid());
      one.set(docMapping.getDocno(doc.getDocid()));
      context.write(docid, one);
    }
  }

  /**
   * Creates an instance of this tool.
   */
  public CountTrecDocuments() {}

  public static final String COLLECTION_OPTION = "collection";
  public static final String OUTPUT_OPTION = "output";
  public static final String MAPPING_OPTION = "docnoMapping";
  public static final String COUNT_OPTION = "countOutput";

  /**
   * Runs this tool.
   */
  @SuppressWarnings("static-access")
  public int run(String[] args) throws Exception {
    Options options = new Options();
    options.addOption(OptionBuilder.withArgName("path").hasArg()
        .withDescription("(required) collection path").create(COLLECTION_OPTION));
    options.addOption(OptionBuilder.withArgName("path").hasArg()
        .withDescription("(required) output path").create(OUTPUT_OPTION));
    options.addOption(OptionBuilder.withArgName("path").hasArg()
        .withDescription("(required) DocnoMapping data").create(MAPPING_OPTION));
    options.addOption(OptionBuilder.withArgName("path").hasArg()
        .withDescription("(optional) output file to write the number of records").create(COUNT_OPTION));

    CommandLine cmdline;
    CommandLineParser parser = new GnuParser();
    try {
      cmdline = parser.parse(options, args);
    } catch (ParseException exp) {
      System.err.println("Error parsing command line: " + exp.getMessage());
      return -1;
    }

    if (!cmdline.hasOption(COLLECTION_OPTION) || !cmdline.hasOption(OUTPUT_OPTION) ||
        !cmdline.hasOption(MAPPING_OPTION)) {
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp(this.getClass().getName(), options);
      ToolRunner.printGenericCommandUsage(System.out);
      return -1;
    }

    String inputPath = cmdline.getOptionValue(COLLECTION_OPTION);
    String outputPath = cmdline.getOptionValue(OUTPUT_OPTION);
    String mappingFile = cmdline.getOptionValue(MAPPING_OPTION);

    LOG.info("Tool: " + CountTrecDocuments.class.getSimpleName());
    LOG.info(" - input: " + inputPath);
    LOG.info(" - output dir: " + outputPath);
    LOG.info(" - docno mapping file: " + mappingFile);

    Job job = new Job(getConf(), CountTrecDocuments.class.getSimpleName());
    job.setJarByClass(CountTrecDocuments.class);

    job.setNumReduceTasks(0);

    // Pass in the class name as a String; this is makes the mapper general in being able to load
    // any collection of Indexable objects that has docid/docno mapping specified by a DocnoMapping
    // object.
    job.getConfiguration().set("DocnoMappingClass", TrecDocnoMapping.class.getCanonicalName());

    // Put the mapping file in the distributed cache so each map worker will have it.
    DistributedCache.addCacheFile(new URI(mappingFile), job.getConfiguration());

    FileInputFormat.setInputPaths(job, new Path(inputPath));
    FileOutputFormat.setOutputPath(job, new Path(outputPath));
    FileOutputFormat.setCompressOutput(job, false);

    job.setInputFormatClass(TrecDocumentInputFormat.class);
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(IntWritable.class);

    job.setMapperClass(MyMapper.class);

    // Delete the output directory if it exists already.
    FileSystem.get(job.getConfiguration()).delete(new Path(outputPath), true);

    job.waitForCompletion(true);

    Counters counters = job.getCounters();
    int numDocs = (int) counters.findCounter(Count.DOCS).getValue();
    LOG.info("Read " + numDocs + " docs.");

    if (cmdline.hasOption(COUNT_OPTION)) {
      String f = cmdline.getOptionValue(COUNT_OPTION);
      FileSystem fs = FileSystem.get(getConf());
      FSDataOutputStream out = fs.create(new Path(f));
      out.write(new Integer(numDocs).toString().getBytes());
      out.close();
    }
  
    return numDocs;
  }

  /**
   * Dispatches command-line arguments to the tool via the {@code ToolRunner}.
   */
  public static void main(String[] args) throws Exception {
    LOG.info("Running " + CountTrecDocuments.class.getCanonicalName() +
        " with args " + Arrays.toString(args));
    ToolRunner.run(new CountTrecDocuments(), args);
  }
}
