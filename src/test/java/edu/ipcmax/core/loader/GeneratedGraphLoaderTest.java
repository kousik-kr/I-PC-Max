package edu.ipcmax.core.loader;

import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeneratedGraphLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsTinyGeneratedGraphAndSparseScores() throws Exception {
        writeGzip("nodes.csv.gz", """
                node_id,x,y
                1,100,100
                2,200,200
                3,300,300
                """);
        writeGzip("edges_static.csv.gz", """
                arc_id,u,v,distance,base_travel_time
                0,1,2,100,10
                1,2,3,200,20
                2,1,3,500,50
                3,1,2,120,12
                """);
        writeGzip("travel_time_functions.jsonl.gz", """
                {"arc_id":0,"u":1,"v":2,"distance":100,"base_travel_time":10,"travel_time_breakpoints":[[0,10.0],[420,10.0],[510,18.0],[600,10.0],[1020,10.0],[1110,20.0],[1200,10.0],[1440,10.0]]}
                {"arc_id":1,"u":2,"v":3,"distance":200,"base_travel_time":20,"travel_time_breakpoints":[[0,20.0],[1440,20.0]]}
                {"arc_id":2,"u":1,"v":3,"distance":500,"base_travel_time":50,"travel_time_breakpoints":[[0,50.0],[1440,50.0]]}
                {"arc_id":3,"u":1,"v":2,"distance":120,"base_travel_time":12,"travel_time_breakpoints":[[0,12.0],[1440,12.0]]}
                """);
        writeGzip("score_functions.jsonl.gz", """
                {"arc_id":0,"u":1,"v":2,"selected_for_score":true,"score_intervals":[[0,420,0],[420,480,4],[480,540,9],[540,600,6],[600,1020,0],[1020,1080,8],[1080,1140,12],[1140,1200,7],[1200,1440,0]]}
                """);
        Files.writeString(tempDir.resolve("manifest.json"), """
                {
                  "num_nodes": 3,
                  "num_arcs": 4,
                  "seed": 42,
                  "selected_score_edge_count": 1,
                  "unlisted_edges_have_score_zero": true
                }
                """, StandardCharsets.UTF_8);

        GeneratedGraphDataset dataset = new GeneratedGraphLoader().load(tempDir);
        TDGraph graph = dataset.graph();

        assertEquals(3, graph.nodeCount());
        assertEquals(4, graph.edgeCount());
        assertEquals(1, dataset.manifest().selectedScoreEdgeCount());
        assertEquals(List.of(0, 3), graph.outgoingEdges(1).stream()
                .filter(edge -> edge.target() == 2)
                .map(Edge::arcId)
                .toList());
        assertEquals(9, graph.edges().get(0).scoreFunction().valueAt(500));
        assertEquals(0, graph.edges().get(1).scoreFunction().valueAt(500));
    }

    private void writeGzip(String fileName, String content) throws IOException {
        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(tempDir.resolve(fileName)));
             OutputStreamWriter writer = new OutputStreamWriter(gzip, StandardCharsets.UTF_8)) {
            writer.write(content.stripIndent());
        }
    }
}
