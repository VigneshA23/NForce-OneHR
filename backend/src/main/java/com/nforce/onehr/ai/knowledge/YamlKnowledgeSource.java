package com.nforce.onehr.ai.knowledge;

import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.ai.contract.KnowledgeDocument;
import com.nforce.onehr.ai.contract.KnowledgeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads authored knowledge from YAML under {@code ai-knowledge/}.
 *
 * <p>Knowledge here describes how OneHR actually behaves, so it belongs beside the code it
 * describes: it reviews as a diff, ships with the change that made it true, and cannot drift into
 * a state where production says one thing and the assistant says another without someone seeing it
 * in a pull request.
 *
 * <p>Parsing is strict and throws on the first malformed unit. A knowledge file that half-loads is
 * worse than one that fails: the assistant would answer confidently from whatever survived, and
 * nobody would know a section was missing.
 */
@Component
@Slf4j
public class YamlKnowledgeSource implements KnowledgeSource {

    private static final String PATTERN = "classpath*:ai-knowledge/**/*.yaml";

    /** The page registry is configuration for navigation, not retrievable knowledge. */
    private static final String REGISTRY_SUFFIX = "pages/registry.yaml";

    @Override
    public String name() {
        return "yaml";
    }

    @Override
    public String sourceRefPrefix() {
        return "yaml:";
    }

    @Override
    public List<KnowledgeDocument> load() {
        List<KnowledgeDocument> documents = new ArrayList<>();
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(PATTERN);
        } catch (Exception e) {
            throw new IllegalStateException("Could not scan " + PATTERN, e);
        }

        for (Resource resource : resources) {
            String path = describe(resource);
            if (path.endsWith(REGISTRY_SUFFIX)) continue;
            documents.addAll(parseFile(resource, path));
        }
        log.info("Loaded {} knowledge documents from YAML", documents.size());
        return documents;
    }

    private List<KnowledgeDocument> parseFile(Resource resource, String path) {
        Map<String, Object> root;
        try (InputStream in = resource.getInputStream()) {
            root = new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read knowledge file " + path, e);
        }
        if (root == null) return List.of();
        if (!(root.get("knowledge") instanceof List<?> units)) {
            throw new IllegalStateException(path + " must contain a top-level 'knowledge' list");
        }

        List<KnowledgeDocument> documents = new ArrayList<>();
        for (Object entry : units) {
            if (!(entry instanceof Map<?, ?> unit)) {
                throw new IllegalStateException("Each entry under 'knowledge' must be a mapping in " + path);
            }
            documents.add(toDocument(unit, path));
        }
        return documents;
    }

    private KnowledgeDocument toDocument(Map<?, ?> unit, String path) {
        String knowledgeId = requireString(unit.get("knowledgeId"), "knowledgeId", path);

        return KnowledgeDocument.builder()
                .knowledgeId(knowledgeId)
                .type(KnowledgeType.fromCode(requireString(unit.get("type"), "type for " + knowledgeId, path))
                        .orElseThrow(() -> new IllegalStateException(
                                "Unknown knowledge type '" + unit.get("type") + "' for " + knowledgeId + " in " + path)))
                .module(optionalString(unit.get("module")))
                .pageId(optionalString(unit.get("pageId")))
                .actionId(optionalString(unit.get("actionId")))
                .workflowId(optionalString(unit.get("workflowId")))
                .version(unit.get("version") instanceof Integer v ? v : 1)
                .audiences(parseAudiences(unit.get("audience"), knowledgeId, path))
                // Carries the file it came from, not just the source name, so a wrong answer can be
                // traced to the exact file to fix from the interaction log alone.
                .sourceRef(sourceRefPrefix() + path)
                .title(requireString(unit.get("title"), "title for " + knowledgeId, path))
                .body(requireString(unit.get("body"), "body for " + knowledgeId, path))
                .synonyms(parseStringList(unit.get("synonyms")))
                .metadata(parseMetadata(unit.get("metadata")))
                .build();
    }

    private Set<AudienceBucket> parseAudiences(Object raw, String knowledgeId, String path) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            // Fail-closed: no audience rows in the index means readable by nobody, so an un-tagged
            // unit would silently vanish rather than be wrongly visible. Catching it here makes it
            // a build failure with a filename instead of a mysterious retrieval gap.
            throw new IllegalStateException(
                    "Knowledge unit " + knowledgeId + " in " + path + " declares no audience");
        }
        Set<AudienceBucket> audiences = new LinkedHashSet<>();
        for (Object value : list) {
            audiences.add(AudienceBucket.fromCode(String.valueOf(value))
                    .orElseThrow(() -> new IllegalStateException(
                            "Unknown audience '" + value + "' on " + knowledgeId + " in " + path)));
        }
        return audiences;
    }

    private List<String> parseStringList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> values = new ArrayList<>();
        for (Object value : list) {
            if (value != null && !String.valueOf(value).isBlank()) values.add(String.valueOf(value).trim());
        }
        return List.copyOf(values);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(Object raw) {
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String requireString(Object value, String what, String path) {
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalStateException("Missing or blank " + what + " in " + path);
        }
        return s.trim();
    }

    private String optionalString(Object value) {
        return value instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    /** Classpath-relative path, so sourceRef reads the same from a jar or an exploded build. */
    private String describe(Resource resource) {
        try {
            String url = resource.getURL().toString();
            int marker = url.indexOf("ai-knowledge/");
            return marker >= 0 ? url.substring(marker) : url;
        } catch (Exception e) {
            return String.valueOf(resource.getFilename());
        }
    }
}
