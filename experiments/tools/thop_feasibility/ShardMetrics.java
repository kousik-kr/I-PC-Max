import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public class ShardMetrics {
  public static void main(String[] a) throws Exception {
    Path dir=Path.of(a[0]); long profiles=0,pieces=0,bytes=0; int maxBits=0,maxPieces=0,sources=0;
    List<String> rows=new ArrayList<>();
    try (DirectoryStream<Path> stream=Files.newDirectoryStream(dir,"source-*.directory")) {
      for(Path index:stream) {
        int source=Integer.parseInt(index.getFileName().toString().replace("source-","").replace(".directory",""));
        Path data=dir.resolve("source-"+source+".profiles");
        try(DataInputStream in=new DataInputStream(Files.newInputStream(index)); RandomAccessFile raf=new RandomAccessFile(data.toFile(),"r")) {
          int stored=in.readInt(),count=in.readInt(); if(stored!=source) throw new IOException("source mismatch");
          long sourcePieces=0; int sourceBits=0;
          for(int i=0;i<count;i++) {
            int destination=in.readInt(); long offset=in.readLong(); int length=in.readInt(); raf.seek(offset);
            if(raf.readInt()!=destination || raf.readInt()!=length) throw new IOException("directory mismatch");
            byte[] encoded=new byte[length]; raf.readFully(encoded);
            ExactArrivalProfile profile=ExactArrivalProfile.readFrom(new DataInputStream(new ByteArrayInputStream(encoded)));
            int countPieces=Math.toIntExact(profile.pieceCount()); sourcePieces+=countPieces; sourceBits=Math.max(sourceBits,profile.maximumRationalComponentBitLength());
            maxPieces=Math.max(maxPieces,countPieces); bytes+=length;
          }
          profiles+=count; pieces+=sourcePieces; maxBits=Math.max(maxBits,sourceBits); sources++;
          rows.add("source="+source+" profiles="+count+" pieces="+sourcePieces+" max_bits="+sourceBits);
        }
      }
    }
    Collections.sort(rows); for(String row:rows) System.out.println(row);
    System.out.println("sources="+sources+" profiles="+profiles+" pieces="+pieces+" max_rational_component_bits="+maxBits+" max_pieces_per_profile="+maxPieces+" profile_bytes="+bytes);
  }
}
