package sssj;

import static sssj.base.Commons.*;
import static sssj.base.Commons.IndexType.INVERTED;

import java.io.File;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sssj.base.Commons;
import sssj.base.Commons.BatchResult;
import sssj.base.Commons.IndexType;
import sssj.base.Vector;
import sssj.index.APIndex;
import sssj.index.Index;
import sssj.index.InvertedIndex;
import sssj.index.L2APIndex;
import sssj.index.VectorWindow;
import sssj.io.Format;
import sssj.io.VectorStream;
import sssj.io.VectorStreamFactory;
import sssj.time.Timeline.Sequential;

import com.github.gdfm.shobaidogu.ProgressTracker;

/**
 * MiniBatch micro-batch method. Keeps a buffer of vectors of length 2*tau. When the buffer is full, index and query the first half of the vectors with a batch
 * index (Inverted, AP, L2AP), and query the index built so far with the second half of the buffer. Discard the first half of the buffer, retain the second half
 * as the new first half, and repeat the process.
 */
public class MiniBatch {
  private static final String ALGO = "MiniBatch";
  private static final Logger log = LoggerFactory.getLogger(MiniBatch.class);

  public static void main(String[] args) throws Exception {
    ArgumentParser parser = ArgumentParsers.newArgumentParser(ALGO).description("SSSJ in " + ALGO + " mode.")
        .defaultHelp(true);
    parser.addArgument("-t", "--theta").metavar("theta").type(Double.class).choices(Arguments.range(0.0, 1.0))
        .setDefault(DEFAULT_THETA).help("similarity threshold");
    parser.addArgument("-l", "--lambda").metavar("lambda").type(Double.class)
        .choices(Arguments.range(0.0, Double.MAX_VALUE)).setDefault(DEFAULT_LAMBDA).help("forgetting factor");
    parser.addArgument("-r", "--report").metavar("period").type(Integer.class).setDefault(DEFAULT_REPORT_PERIOD)
        .help("progress report period");
    parser.addArgument("-i", "--index").type(IndexType.class).choices(IndexType.values()).setDefault(INVERTED)
        .help("type of indexing");
    parser.addArgument("-f", "--format").type(Format.class).choices(Format.values()).setDefault(Format.BINARY)
        .help("input format");
    parser.addArgument("input").metavar("file")
        .type(Arguments.fileType().verifyExists().verifyIsFile().verifyCanRead()).help("input file");
    Namespace opts = parser.parseArgsOrFail(args);

    final double theta = opts.get("theta");
    final double lambda = opts.get("lambda");
    final int reportPeriod = opts.getInt("report");
    final IndexType idxType = opts.<IndexType>get("index");
    final Format fmt = opts.<Format>get("format");
    final File file = opts.<File>get("input");
    final VectorStream stream = VectorStreamFactory.getVectorStream(file, fmt, new Sequential());
    final int numVectors = stream.numVectors();
    final ProgressTracker tracker = new ProgressTracker(numVectors, reportPeriod);

    System.out.println(String.format(ALGO + " [t=%f, l=%f, i=%s]", theta, lambda, idxType.toString()));
    log.info(String.format(ALGO + " [t=%f, l=%f, i=%s]", theta, lambda, idxType.toString()));
    long start = System.currentTimeMillis();
    compute(stream, theta, lambda, idxType, tracker);
    long elapsed = System.currentTimeMillis() - start;
    System.out.println(String.format(ALGO + "-%s, %f, %f, %d", idxType.toString(), theta, lambda, elapsed));
    log.info(String.format(ALGO + " [t=%f, l=%f, i=%s, time=%d]", theta, lambda, idxType.toString(), elapsed));
  }

  public static void compute(Iterable<Vector> stream, double theta, double lambda, IndexType idxType,
      ProgressTracker tracker) {
    final double tau = Commons.tau(theta, lambda);
    System.out.println("Tau = " + tau);
    precomputeFFTable(lambda, 2 * (int) Math.ceil(tau));
    VectorWindow window = new VectorWindow(tau, idxType.needsMax());

    for (Vector v : stream) {
      if (tracker != null)
        tracker.progress();
      boolean inWindow = window.add(v);
      while (!inWindow) {
        if (window.size() > 0)
          computeResults(window, theta, lambda, idxType);
        else
          window.slide();
        inWindow = window.add(v);
      }
    }
    // last 2 window slides
    while (!window.isEmpty())
      computeResults(window, theta, lambda, idxType);
  }

  private static void computeResults(VectorWindow window, double theta, double lambda, IndexType type) {
    // select and initialize index
    Index index = null;
    switch (type) {
    case INVERTED:
      index = new InvertedIndex(theta, lambda);
      break;
    case ALLPAIRS:
      index = new APIndex(theta, lambda, window.getMax());
      break;
    case L2AP:
      index = new L2APIndex(theta, lambda, window.getMax());
      break;
    default:
      throw new RuntimeException("Unsupported index type");
    }
    assert (index != null);

    // query and build the index on first half of the buffer
    BatchResult res1 = query(index, window.firstHalf(), true);
    // query the index with the second half of the buffer without indexing it
    BatchResult res2 = query(index, window.secondHalf(), false);
    // slide the window
    window.slide();

    // print results
    for (Entry<Long, Map<Long, Double>> row : res1.rowMap().entrySet()) {
      System.out.println(row.getKey() + " ~ " + formatMap(row.getValue()));
    }
    for (Entry<Long, Map<Long, Double>> row : res2.rowMap().entrySet()) {
      System.out.println(row.getKey() + " ~ " + formatMap(row.getValue()));
    }
  }

  private static BatchResult query(Index index, Iterator<Vector> iterator, final boolean addToIndex) {
    BatchResult result = new BatchResult();
    while (iterator.hasNext()) {
      Vector v = iterator.next();
      Map<Long, Double> matches = index.queryWith(v, addToIndex);
      for (Entry<Long, Double> e : matches.entrySet()) {
        result.put(v.timestamp(), e.getKey(), e.getValue());
      }
    }
    return result;
  }
}
