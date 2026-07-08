package me.bechberger.hprof.views;
import java.nio.file.Path;

public class DebugNames {
    public static void main(String[] args) throws Exception {
        Path p = Path.of(args[0]);
        HeapGraph g = new HeapGraphBuilder(p).build();
        int questionMarks = 0;
        int total = g.classList.size();
        for (int i = 0; i < Math.min(total, 10); i++) {
            System.out.println("class[" + i + "]: " + g.classList.get(i).name());
        }
        for (int i = 0; i < total; i++) {
            if ("?".equals(g.classList.get(i).name())) questionMarks++;
        }
        System.out.println("Total: " + total + ", question marks: " + questionMarks);
    }
}
