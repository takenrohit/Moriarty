package dev.investigator.runbook;

import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Runbook_Printer (Req 12.4): writes {@code ---}, the front-matter with keys in the order
 * {@code title}, {@code service}, {@code symptoms}, {@code tags}, {@code ---}, then the body verbatim.
 *
 * <p>The front-matter is dumped with SnakeYAML in block style without line folding, so values with
 * YAML-special characters are quoted and each value stays on one line. {@link RunbookParser} reads the
 * output back to an equal {@link Runbook} (Req 12.5).
 */
public final class RunbookPrinter {

    public String print(Runbook runbook) {
        Map<String, Object> frontMatter = new LinkedHashMap<>();
        frontMatter.put("title", runbook.title());
        frontMatter.put("service", runbook.service());
        frontMatter.put("symptoms", runbook.symptoms());
        frontMatter.put("tags", runbook.tags());

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setSplitLines(false);
        options.setWidth(Integer.MAX_VALUE);
        options.setLineBreak(DumperOptions.LineBreak.UNIX);
        String yaml = new Yaml(options).dump(frontMatter);

        StringBuilder out = new StringBuilder(yaml.length() + runbook.body().length() + 8);
        out.append(RunbookParser.DELIMITER).append('\n').append(yaml);
        if (!yaml.endsWith("\n")) {
            out.append('\n');
        }
        return out.append(RunbookParser.DELIMITER).append('\n').append(runbook.body()).toString();
    }
}
