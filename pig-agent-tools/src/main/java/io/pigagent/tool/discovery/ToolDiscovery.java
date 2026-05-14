package io.pigagent.tool.discovery;

import io.agentscope.core.tool.Tool;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class ToolDiscovery {

    private static final int MAX_RESULTS = 10;

    private final List<ToolEntry> entries = new ArrayList<>();
    private final Directory index = new ByteBuffersDirectory();
    private final StandardAnalyzer analyzer = new StandardAnalyzer();
    private boolean dirty = true;

    public void register(Object toolInstance) {
        for (Method method : toolInstance.getClass().getDeclaredMethods()) {
            Tool annotation = method.getAnnotation(Tool.class);
            if (annotation != null) {
                entries.add(new ToolEntry(toolInstance, method, annotation.name(), annotation.description()));
                dirty = true;
            }
        }
    }

    public List<ToolEntry> search(String query) {
        if (entries.isEmpty()) {
            return List.of();
        }
        try {
            return luceneSearch(query);
        } catch (Exception e) {
            return fallbackSearch(query);
        }
    }

    public List<ToolEntry> getAll() {
        return List.copyOf(entries);
    }

    private List<ToolEntry> luceneSearch(String query) throws Exception {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        try (IndexWriter writer = new IndexWriter(index, config)) {
            writer.deleteAll();
            for (int i = 0; i < entries.size(); i++) {
                ToolEntry entry = entries.get(i);
                Document doc = new Document();
                doc.add(new TextField("idx", String.valueOf(i), Field.Store.YES));
                doc.add(new TextField("name", entry.name(), Field.Store.YES));
                doc.add(new TextField("description", entry.description(), Field.Store.YES));
                doc.add(new TextField("all", entry.name() + " " + entry.description(), Field.Store.NO));
                writer.addDocument(doc);
            }
            writer.commit();
        }

        try (DirectoryReader reader = DirectoryReader.open(index)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            QueryParser parser = new QueryParser("all", analyzer);
            TopDocs topDocs = searcher.search(parser.parse(QueryParser.escape(query)), MAX_RESULTS);

            List<ToolEntry> results = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                int idx = Integer.parseInt(doc.get("idx"));
                results.add(entries.get(idx));
            }
            return results;
        }
    }

    private List<ToolEntry> fallbackSearch(String query) {
        String lower = query.toLowerCase();
        return entries.stream()
                .filter(e -> e.name().toLowerCase().contains(lower) || e.description().toLowerCase().contains(lower))
                .toList();
    }

    public record ToolEntry(Object instance, Method method, String name, String description) {}
}
