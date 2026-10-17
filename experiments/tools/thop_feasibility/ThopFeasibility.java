import com.fasterxml.jackson.databind.ObjectMapper;
import edu.ipcmax.baselines.BoundaryTHopIndex;
import edu.ipcmax.baselines.GraphPartitionMetadata;
import edu.ipcmax.baselines.PartitionedTHopIndex;
import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.loader.GeneratedGraphDataset;
import edu.ipcmax.core.loader.GeneratedGraphLoader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public class ThopFeasibility {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Domain DOMAIN = Domain.closed(0, 10080);
  static String sha256(byte[] data) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
  }
  static long peakRssKb() throws Exception {
    for (String line : Files.readAllLines(Path.of("/proc/self/status")))
      if (line.startsWith("VmHWM:")) return Long.parseLong(line.trim().split("\\s+")[1]);
    return -1;
  }
  static String assignmentHash(Map<Integer,Integer> a) throws Exception {
    MessageDigest d = MessageDigest.getInstance("SHA-256");
    for (var e : new TreeMap<>(a).entrySet()) d.update((e.getKey()+":"+e.getValue()+"\n").getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(d.digest());
  }
  static Map<Integer,Integer> spatial(TDGraph g, Collection<Integer> ids, int levels) {
    TreeMap<Integer,Integer> out = new TreeMap<>();
    split(g, new ArrayList<>(ids), levels, 0, 0, out);
    return out;
  }
  static void split(TDGraph g, List<Integer> ids, int levels, int depth, int leaf, Map<Integer,Integer> out) {
    if (depth == levels) { for (int id : ids) out.put(id, leaf); return; }
    int axis = depth % 2;
    ids.sort(Comparator.comparingLong((Integer id) -> axis == 0 ? g.node(id).x() : g.node(id).y())
      .thenComparingLong(id -> axis == 0 ? g.node(id).y() : g.node(id).x()).thenComparingInt(Integer::intValue));
    int middle = ids.size()/2;
    split(g, new ArrayList<>(ids.subList(0,middle)), levels, depth+1, leaf*2, out);
    split(g, new ArrayList<>(ids.subList(middle,ids.size())), levels, depth+1, leaf*2+1, out);
  }
  static Map<String,Object> metadata(TDGraph graph, Map<Integer,Integer> assignments, String hash) {
    GraphPartitionMetadata m = GraphPartitionMetadata.fromAssignments(graph, assignments);
    Map<String,Object> sizes = new TreeMap<>();
    for (var e : m.verticesByPartition().entrySet()) sizes.put(String.valueOf(e.getKey()), e.getValue().size());
    return new LinkedHashMap<>(Map.of(
      "vertices",graph.nodeCount(),"directed_arcs",graph.edgeCount(),
      "partition_count",m.partitionCount(),"partition_sizes",sizes,
      "boundary_vertices",m.boundaryVertexCount(),"cross_partition_directed_arcs",m.crossPartitionArcCount(),
      "assignment_sha256",hash,"partition_algorithm","recursive spatial median bisection; x/y alternate; ties y/x/node_id; seed=none"));
  }
  static List<Integer> bfs(TDGraph graph, int count) {
    ArrayList<Integer> order = new ArrayList<>(count);
    HashSet<Integer> seen = new HashSet<>();
    ArrayDeque<Integer> queue = new ArrayDeque<>();
    int start = graph.nodeIds().getFirst(); seen.add(start); queue.add(start);
    while (!queue.isEmpty() && order.size() < count) {
      int u = queue.removeFirst(); order.add(u);
      TreeMap<Integer,Integer> neighbors = new TreeMap<>();
      for (Edge e : graph.outgoingEdges(u)) neighbors.put(e.arcId(),e.target());
      for (Edge e : graph.incomingEdges(u)) neighbors.put(e.arcId(),e.source());
      for (int v : neighbors.values()) if (seen.add(v)) queue.addLast(v);
    }
    if (order.size() < count) throw new IllegalArgumentException("seed component only has "+order.size()+" vertices, requested "+count);
    return order;
  }
  static TDGraph induced(TDGraph source, List<Integer> ids) {
    Set<Integer> selected = new HashSet<>(ids);
    List<Node> nodes = ids.stream().map(source::node).toList();
    List<Edge> edges = new ArrayList<>();
    for (Edge e : source.edges()) if (selected.contains(e.source()) && selected.contains(e.target()))
      edges.add(new Edge(edges.size(),e.source(),e.target(),e.distance(),e.baseTravelTime(),e.travelTimeFunction(),e.scoreFunction()));
    return new TDGraph(nodes,edges);
  }
  static int maxBoundaryBits(BoundaryTHopIndex b) {
    int max=0;
    for (int id : b.partitionMetadata().boundaryVertexIds()) {
      for (ExactArrivalProfile p : b.exactOutgoingLabels(id).values()) max=Math.max(max,p.maximumRationalComponentBitLength());
      for (ExactArrivalProfile p : b.exactIncomingLabels(id).values()) max=Math.max(max,p.maximumRationalComponentBitLength());
    }
    return max;
  }
  static Map<String,Object> buildOne(TDGraph graph, Path root, String dataset, int size, int ordinal) throws Exception {
    List<Integer> ids=bfs(graph,size);
    TDGraph sub=induced(graph,ids);
    Map<Integer,Integer> assign=spatial(sub,sub.nodeIds(),2);
    GraphPartitionMetadata md=GraphPartitionMetadata.fromAssignments(sub,assign);
    Path dir=root.resolve(dataset+"-n"+size+"-run"+ordinal); Files.createDirectories(dir);
    Path boundary=dir.resolve("boundary.bthp"); Path inner=dir.resolve("inner.pthp");
    long b0=System.nanoTime();
    BoundaryTHopIndex b=BoundaryTHopIndex.build(sub,md,DOMAIN,boundary,md.boundaryVertexIds(),1);
    long boundaryNanos=System.nanoTime()-b0;
    long i0=System.nanoTime();
    PartitionedTHopIndex built=PartitionedTHopIndex.build(sub,boundary,inner);
    long innerNanos=System.nanoTime()-i0;
    long l0=System.nanoTime();
    PartitionedTHopIndex loaded=PartitionedTHopIndex.load(sub,boundary,inner);
    long loadNanos=System.nanoTime()-l0;
    int source=0,destination=0;
    outer: for (int x : md.boundaryVertexIds()) for (int y : md.boundaryVertexIds())
      if (x!=y && !Objects.equals(assign.get(x),assign.get(y))) {source=x;destination=y;break outer;}
    long q0=System.nanoTime();
    var result=loaded.query(source,destination,DOMAIN);
    long queryNanos=System.nanoTime()-q0;
    var boundaryProfile=b.query(source,destination,DOMAIN).exactTravelTimeProfile();
    boolean boundaryAgreement=boundaryProfile.sameFunction(result.exactTravelTimeProfile());
    Map<String,Object> metrics=new LinkedHashMap<>();
    metrics.put("dataset",dataset); metrics.put("induced_vertices",sub.nodeCount()); metrics.put("induced_directed_arcs",sub.edgeCount());
    metrics.put("partition_count",md.partitionCount()); metrics.put("partition_sizes",md.verticesByPartition().values().stream().map(List::size).toList());
    metrics.put("boundary_vertices",md.boundaryVertexCount()); metrics.put("cross_partition_directed_arcs",md.crossPartitionArcCount());
    metrics.put("assignment_sha256",assignmentHash(assign)); metrics.put("boundary_index_sha256",sha256(Files.readAllBytes(boundary)));
    metrics.put("inner_index_sha256",sha256(Files.readAllBytes(inner)));
    metrics.put("boundary_preprocessing_nanos",boundaryNanos); metrics.put("inner_preprocessing_nanos",innerNanos); metrics.put("index_loading_nanos",loadNanos);
    metrics.put("boundary_searches",b.constructionStatistics().boundaryProfileSearchCalls());
    metrics.put("boundary_generated_pieces",b.constructionStatistics().generatedFunctionPieces());
    metrics.put("boundary_retained_pieces",b.constructionStatistics().retainedFunctionPieces());
    metrics.put("boundary_temporal_compositions",b.constructionStatistics().temporalCompositions());
    metrics.put("inner_searches",built.constructionStatistics().searchCalls());
    metrics.put("inner_generated_pieces",built.constructionStatistics().temporalPieces());
    metrics.put("inner_temporal_compositions",built.constructionStatistics().temporalCompositions());
    metrics.put("boundary_max_rational_component_bits",maxBoundaryBits(b));
    metrics.put("sampled_peak_jvm_heap_bytes",Math.max(b.constructionStatistics().sampledPeakUsedHeapBytes(),built.constructionStatistics().sampledPeakUsedHeapBytes()));
    metrics.put("process_peak_rss_kb",peakRssKb());
    metrics.put("query_result_profile_pieces",result.exactTravelTimeProfile().pieceCount());
    metrics.put("query_result_max_rational_component_bits",result.exactTravelTimeProfile().maximumRationalComponentBitLength());
    metrics.put("query_nanos_including_lazy_load",queryNanos); metrics.put("query_lazy_load_nanos",result.queryStatistics().lazyLoadNanos());
    metrics.put("query_logical_disk_bytes_read",result.queryStatistics().diskBytesRead()); metrics.put("query_boundary_agrees_with_partitioned",boundaryAgreement);
    metrics.put("boundary_index_bytes",Files.size(boundary)); metrics.put("inner_index_bytes",Files.size(inner)); metrics.put("total_index_bytes",Files.size(boundary)+Files.size(inner));
    metrics.put("temporary_directory",dir.toString());
    return metrics;
  }
  public static void main(String[] args) throws Exception {
    String dataset=args[0]; Path graphDir=Path.of(args[1]); Path out=Path.of(args[2]); Files.createDirectories(out);
    GeneratedGraphDataset loaded=new GeneratedGraphLoader().load(graphDir); TDGraph graph=loaded.graph();
    Map<Integer,Integer> full=spatial(graph,graph.nodeIds(),6);
    Map<String,Object> fullMetrics=metadata(graph,full,assignmentHash(full));
    fullMetrics.put("dataset",dataset); fullMetrics.put("manifest",graphDir.resolve("manifest.json").toString());
    fullMetrics.put("manifest_graph_checksum",loaded.manifest()==null?null:loaded.manifest().datasetChecksum().orElse(null));
    fullMetrics.put("full_index_source_search_lower_bound",((GraphPartitionMetadata.fromAssignments(graph,full)).boundaryVertexCount()));
    fullMetrics.put("process_peak_rss_kb",peakRssKb());
    System.out.println(JSON.writeValueAsString(Map.of("full_partition",fullMetrics)));
    if (args[3].startsWith("meta:")) {
      for (String raw : args[3].substring(5).split(",")) {
        int n=Integer.parseInt(raw);
        TDGraph sub=induced(graph,bfs(graph,n));
        Map<Integer,Integer> assignment=spatial(sub,sub.nodeIds(),2);
        Map<String,Object> row=metadata(sub,assignment,assignmentHash(assignment));
        row.put("dataset",dataset); row.put("induced_from_full_graph",true);
        System.out.println(JSON.writeValueAsString(Map.of("induced_partition",row)));
      }
      return;
    }
    for (String raw : args[3].split(",")) {
      int n=Integer.parseInt(raw);
      try { System.out.println(JSON.writeValueAsString(buildOne(graph,out,dataset,n,1))); }
      catch (Throwable failure) { failure.printStackTrace(System.err); System.out.println(JSON.writeValueAsString(Map.of("dataset",dataset,"induced_vertices",n,"failure",failure.toString()))); }
    }
  }
}
