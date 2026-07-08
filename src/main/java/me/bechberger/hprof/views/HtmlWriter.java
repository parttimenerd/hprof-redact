/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import me.bechberger.util.json.JSONStringUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads report-template.html from classpath, splices a JSON data blob and pre-rendered
 * HTML table rows into {{PLACEHOLDER}} slots, and writes a single self-contained .html file.
 */
public final class HtmlWriter {

    private final HeapGraph graph;
    private final double thresholdPct;

    public HtmlWriter(HeapGraph graph) {
        this(graph, LeakSuspectsReport.THRESHOLD_PCT);
    }

    public HtmlWriter(HeapGraph graph, double thresholdPct) {
        this.graph = graph;
        this.thresholdPct = thresholdPct;
    }

    public void writeTo(Path outputPath) throws IOException {
        HtmlReportData.ReportData data = HtmlReportData.compute(graph);
        String template = loadTemplate();
        String html = splice(template, data);
        Files.writeString(outputPath, html, StandardCharsets.UTF_8);
    }

    private String loadTemplate() throws IOException {
        try (InputStream is = HtmlWriter.class.getResourceAsStream("report-template.html")) {
            if (is == null) throw new IOException("report-template.html not found on classpath");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String splice(String tpl, HtmlReportData.ReportData data) {
        long ts = totalShallow();
        return tpl
            .replace("{{DATA}}",        buildJson(data))
            .replace("{{THREADS}}",     buildThreadRows(data.threads(), ts))
            .replace("{{HISTOGRAM}}",   buildHistogramRows(data.histogram(), ts))
            .replace("{{TOP_OBJECTS}}", buildTopObjectRows(data.biggestObjects(), ts))
            .replace("{{TOP_CLASSES}}", buildTopClassRows(data.biggestClasses(), ts))
            .replace("{{TOP_PACKAGES}}",buildTopPackageRows(data.biggestPackages(), ts))
            .replace("{{TOP_LOADERS}}", buildTopLoaderRows(data.biggestClassLoaders(), ts))
            .replace("{{SUSPECTS}}",    buildSuspectsHtml(data.leakSuspects()))
            .replace("{{SYS_PROPS}}",   buildSysPropRows(data.systemProperties()));
    }

    // -- JSON blob --

    private String buildJson(HtmlReportData.ReportData data) {
        HtmlReportData.HeapSummary s = data.summary();
        StringBuilder sb = new StringBuilder("{\"summary\":{");
        sb.append("\"fileName\":").append(jstr(s.fileName())).append(",");
        sb.append("\"hprofFormat\":").append(jstr(s.hprofFormat())).append(",");
        sb.append("\"fileSize\":").append(s.fileSize()).append(",");
        sb.append("\"objectCount\":").append(s.objectCount()).append(",");
        sb.append("\"totalShallowBytes\":").append(s.totalShallowBytes()).append(",");
        sb.append("\"gcRootCount\":").append(s.gcRootCount()).append(",");
        sb.append("\"classCount\":").append(s.classCount()).append(",");
        sb.append("\"generatedAt\":").append(jstr(s.generatedAt()));
        sb.append("},");
        sb.append("\"objectPieSlices\":").append(pieJson(data.objectPieSlices())).append(",");
        sb.append("\"classPieSlices\":").append(pieJson(data.classPieSlices()));
        sb.append("}");
        return sb.toString();
    }

    private static String pieJson(List<HtmlReportData.PieSlice> slices) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < slices.size(); i++) {
            if (i > 0) sb.append(",");
            HtmlReportData.PieSlice sl = slices.get(i);
            sb.append("{\"label\":").append(jstr(sl.label()))
              .append(",\"bytes\":").append(sl.bytes())
              .append(",\"pct\":").append(String.format("%.2f", sl.pct()))
              .append("}");
        }
        return sb.append("]").toString();
    }

    private static String jstr(String s) {
        return "\"" + JSONStringUtil.escapeString(s) + "\"";
    }

    // -- HTML cell helpers --

    private static String h(String s) {
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }
    private static String td(String v)       { return "<td>"+h(v)+"</td>"; }
    private static String tdM(String v)      { return "<td class=\"mono\">"+h(v)+"</td>"; }
    private static String tdN(long v)        { return "<td data-val=\""+v+"\">"+h(fmt(v))+"</td>"; }
    private static String tdC(long v)        { return "<td data-val=\""+v+"\">"+String.format("%,d",v)+"</td>"; }
    private static String tdPct(double p)    { return "<td data-val=\""+String.format("%.4f",p)+"\">"+String.format("%.1f%%",p)+"</td>"; }
    private static String tdBar(long v, long total) {
        double pct = total > 0 ? 100.0*v/total : 0;
        int w = Math.max(2,(int)(pct*1.5));
        return "<td data-val=\""+v+"\"><div class=\"bar-wrap\"><div class=\"bar\" style=\"width:"+w+"px\"></div>"+h(fmt(v))+" ("+String.format("%.1f",pct)+"%)</div></td>";
    }
    private static String fmt(long n) { return SystemOverviewReport.formatBytes(n); }

    // -- Table row builders --

    private static String buildThreadRows(List<HtmlReportData.ThreadEntry> list, long ts) {
        if (list.isEmpty()) return "<tr><td colspan=\"8\" class=\"no-data\">No thread instances found</td></tr>";
        StringBuilder sb = new StringBuilder();
        for (var t : list) sb.append("<tr>").append(td(t.name())).append(tdM(t.hexAddress()))
            .append(tdBar(t.retainedBytes(),ts)).append(tdN(t.shallowBytes()))
            .append(td(t.daemon()?"yes":"no")).append(tdC(t.priority()))
            .append(td(t.state())).append(td(t.contextClassLoader())).append("</tr>");
        return sb.toString();
    }

    private static String buildHistogramRows(List<HtmlReportData.ClassHistogramEntry> list, long ts) {
        StringBuilder sb = new StringBuilder();
        for (var e : list) sb.append("<tr>").append(tdC(e.rank())).append(tdM(e.className()))
            .append(tdC(e.instanceCount())).append(tdN(e.shallowBytes()))
            .append(tdBar(e.groupRetainedBytes(),ts)).append("</tr>");
        return sb.toString();
    }

    private static String buildTopObjectRows(List<HtmlReportData.BiggestObject> list, long ts) {
        StringBuilder sb = new StringBuilder();
        for (var o : list) sb.append("<tr>").append(tdC(o.rank())).append(tdM(o.hexAddress()))
            .append(tdM(o.className())).append(tdN(o.shallowBytes()))
            .append(tdBar(o.retainedBytes(),ts)).append(tdPct(o.retainedPct())).append("</tr>");
        return sb.toString();
    }

    private static String buildTopClassRows(List<HtmlReportData.BiggestClass> list, long ts) {
        StringBuilder sb = new StringBuilder();
        for (var c : list) sb.append("<tr>").append(tdC(c.rank())).append(tdM(c.className()))
            .append(tdC(c.topLevelCount())).append(tdBar(c.retainedBytes(),ts)).append("</tr>");
        return sb.toString();
    }

    private static String buildTopPackageRows(List<HtmlReportData.BiggestPackage> list, long ts) {
        StringBuilder sb = new StringBuilder();
        for (var p : list) sb.append("<tr>").append(tdC(p.rank())).append(tdM(p.packageName()))
            .append(tdC(p.classCount())).append(tdBar(p.retainedBytes(),ts)).append("</tr>");
        return sb.toString();
    }

    private static String buildTopLoaderRows(List<HtmlReportData.BiggestClassLoader> list, long ts) {
        StringBuilder sb = new StringBuilder();
        for (var l : list) sb.append("<tr>").append(tdC(l.rank())).append(tdM(l.loaderName()))
            .append(tdC(l.classCount())).append(tdBar(l.retainedBytes(),ts)).append("</tr>");
        return sb.toString();
    }

    private static String buildSuspectsHtml(List<HtmlReportData.LeakSuspect> suspects) {
        if (suspects.isEmpty()) return "<p class=\"no-data\">No leak suspects found above the 10% threshold.</p>";
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (var s : suspects) {
            sb.append("<div class=\"suspect\">");
            sb.append("<h3>Problem Suspect ").append(n++).append(": <code>").append(h(s.className())).append("</code></h3>");
            if (!s.narrative().isEmpty())
                sb.append("<div class=\"suspect-narrative\">").append(h(s.narrative())).append("</div>");
            sb.append("<div class=\"suspect-meta\">");
            sb.append("<span>").append(s.isSingle()?"Single object":"Group of instances").append("</span>");
            sb.append("<span>Instances: <strong>").append(String.format("%,d",s.instanceCount())).append("</strong></span>");
            sb.append("<span>Retained: <strong>").append(h(fmt(s.retainedBytes())))
              .append("</strong> (").append(String.format("%.1f",s.retainedPct())).append("%)</span>");
            sb.append("<span>Shallow: <strong>").append(h(fmt(s.shallowBytes()))).append("</strong></span>");
            sb.append("</div>");
            if (!s.topConsumers().isEmpty()) {
                sb.append("<h4>Top Consumers</h4><table><thead><tr><th>Class</th><th>Instances</th><th>Shallow</th></tr></thead><tbody>");
                for (var tc : s.topConsumers())
                    sb.append("<tr>").append(tdM(tc.className())).append(tdC(tc.instanceCount())).append(tdN(tc.totalShallowBytes())).append("</tr>");
                sb.append("</tbody></table>");
            }
            if (!s.stackFrames().isEmpty()) {
                sb.append("<h4>Significant Stack Frames</h4><table><thead><tr><th>Method</th><th>Source</th><th>Line</th><th>Local Object</th><th>Retained</th></tr></thead><tbody>");
                for (var f : s.stackFrames()) {
                    String src = f.sourceFile().isEmpty() ? "" : f.sourceFile()+":"+f.lineNumber();
                    sb.append("<tr>").append(tdM(f.methodName()+f.methodSig())).append(tdM(src))
                      .append(td(f.lineNumber()>0?String.valueOf(f.lineNumber()):""))
                      .append(f.localHexAddress().isEmpty() ? "<td></td>" : tdM(f.localHexAddress()+" "+f.localClassName()))
                      .append(f.localRetainedBytes()>0 ? tdN(f.localRetainedBytes()) : "<td></td>")
                      .append("</tr>");
                }
                sb.append("</tbody></table>");
            }
            if (!s.accumulationPath().isEmpty()) {
                sb.append("<h4>Accumulation Path</h4><table><thead><tr><th>Depth</th><th>Address</th><th>Class</th><th>Retained</th></tr></thead><tbody>");
                for (var step : s.accumulationPath())
                    sb.append("<tr>").append(td(String.valueOf(step.depth()))).append(tdM(step.hexAddress()))
                      .append(tdM(step.className())).append(tdN(step.retainedBytes())).append("</tr>");
                sb.append("</tbody></table>");
            }
            sb.append("</div>");
        }
        return sb.toString();
    }

    private static String buildSysPropRows(List<HtmlReportData.SysProp> props) {
        if (props.isEmpty()) return "<tr><td colspan=\"2\" class=\"no-data\">System properties not available</td></tr>";
        StringBuilder sb = new StringBuilder();
        for (var p : props) sb.append("<tr>").append(tdM(p.key())).append(td(p.value())).append("</tr>");
        return sb.toString();
    }

    private long totalShallow() {
        long t = 0;
        for (int i = 1; i < graph.N; i++) t += graph.shallowSizeOf(i);
        return t;
    }
}
