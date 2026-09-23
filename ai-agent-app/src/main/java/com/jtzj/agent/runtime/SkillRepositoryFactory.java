package com.jtzj.agent.runtime;

import com.jtzj.agent.core.config.AgentProperties;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import com.jtzj.agent.core.error.AgentException;
import com.jtzj.agent.core.error.ErrorCodes;

/**
 * Assembles the skill stack and turns a request's {@code skills} selection into a
 * {@link SkillFilter}.
 *
 * <p>Order is low to high priority, which is the order AgentScope merges in: a skill defined
 * in the operator directory shadows a same-named skill bundled in the jar. That lets an
 * deployment patch a shipped skill without rebuilding the image.
 *
 * <p>Both repositories are created once and reused for every task. {@code
 * FileSystemSkillRepository} snapshots each SKILL.md by mtime and size, so editing a skill file
 * is picked up by the next task without a restart — the factory does not need its own cache.
 */
@Component
public class SkillRepositoryFactory {

    private static final Logger log = LoggerFactory.getLogger(SkillRepositoryFactory.class);

    /**
     * Skill names come from directory and frontmatter text and end up inside the system prompt
     * and inside a tool argument; keep them to a conservative token shape. Same intent as the
     * taskId allowlist, and it stops a crafted directory name from smuggling prompt content.
     */
    private static final java.util.regex.Pattern SKILL_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");

    private final AgentProperties props;
    private final List<AgentSkillRepository> repositories;

    public SkillRepositoryFactory(AgentProperties props) {
        this.props = props;
        this.repositories = props.getSkills().isEnabled() ? build() : List.of();
    }

    public boolean enabled() {
        return props.getSkills().isEnabled() && !repositories.isEmpty();
    }

    /** Compose-ordered repositories, low priority first. */
    public List<AgentSkillRepository> repositories() {
        return repositories;
    }

    /**
     * Every visible skill name across all repositories, deduplicated and sorted.
     *
     * <p>Recomputed on each call on purpose: the file-system repository serves edits without a
     * restart, and a cached copy would reject a skill an operator just added.
     */
    public List<String> availableNames() {
        Set<String> names = new LinkedHashSet<>();
        for (AgentSkillRepository repo : repositories) {
            try {
                List<String> repoNames = repo.getAllSkillNames();
                if (repoNames != null) {
                    names.addAll(repoNames);
                }
            } catch (Exception e) {
                log.warn("skill repository {} could not be listed: {}",
                        repo.getClass().getSimpleName(), e.getMessage());
            }
        }
        return new ArrayList<>(new TreeSet<>(names));
    }

    /**
     * @param requested skill names from the request; empty or null means "everything visible"
     */
    public SkillFilter filterFor(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return SkillFilter.all();
        }
        return SkillFilter.only(requested.toArray(new String[0]));
    }

    /**
     * Validate a request's selection against the allowlist and what is actually installed.
     *
     * <p>Unknown names are rejected rather than ignored: a silently empty skill view would let
     * the agent answer confidently without the guidance the caller thought it asked for.
     *
     * @return the normalised, de-duplicated selection (never null)
     * @throws AgentException INVALID_REQUEST on bad shape, unknown names, or over-length lists
     */
    public List<String> validateSelection(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        if (!props.getSkills().isEnabled()) {
            throw new AgentException(ErrorCodes.INVALID_REQUEST,
                    "skills are disabled (agent.skills.enabled=false); remove the skills field", false);
        }
        int cap = Math.max(1, props.getSkills().getMaxRequested());
        List<String> cleaned = new ArrayList<>();
        for (String raw : requested) {
            String name = raw == null ? "" : raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (!SKILL_NAME.matcher(name).matches()) {
                throw new AgentException(ErrorCodes.INVALID_REQUEST,
                        "skill name must match [A-Za-z0-9][A-Za-z0-9._+-]{0,63}: '" + name + "'", false);
            }
            if (!cleaned.contains(name)) {
                cleaned.add(name);
            }
        }
        if (cleaned.size() > cap) {
            throw new AgentException(ErrorCodes.INVALID_REQUEST,
                    "skills accepts at most " + cap + " entries", false);
        }
        if (cleaned.isEmpty()) {
            return List.of();
        }

        // Exact match against the installed names. Deliberately case-sensitive: skill names come
        // from directory names and the later body lookup is exact too, so folding case here would
        // accept a spelling that cannot be loaded.
        Set<String> installed = new TreeSet<>(availableNames());
        List<String> unknown = new ArrayList<>();
        for (String name : cleaned) {
            if (!installed.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            throw new AgentException(ErrorCodes.INVALID_REQUEST,
                    "unknown skill(s) " + unknown + "; available: "
                            + (installed.isEmpty() ? "(none installed)" : installed), false);
        }
        return cleaned;
    }

    /**
     * Concatenate the bodies of the selected skills for direct injection into the task prompt.
     *
     * <p>Explicitly naming a skill means "this task must follow it", so the server enforces it
     * rather than hoping the model asks. Leaving it to {@code load_skill_through_path} is
     * probabilistic: measured on the local qwen endpoint, one run of an instruction loaded the
     * selected skill and produced its required marker, and the next run of the same instruction
     * skipped the tool entirely. Injecting the body is deterministic, costs the same tokens as a
     * successful load, and saves a round-trip.
     *
     * <p>Skills the caller did <em>not</em> name keep progressive disclosure: they stay listed in
     * {@code <available_skills>} as name-and-description only.
     *
     * @return the assembled block, or an empty string when nothing was selected
     */
    public String inlineFor(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            AgentSkill skill = find(name);
            if (skill == null) {
                log.warn("skill '{}' could not be loaded for injection", name);
                continue;
            }
            String body = skill.getSkillContent();
            if (body == null || body.isBlank()) {
                log.warn("skill '{}' has no body to inject", name);
                continue;
            }
            sb.append("===== SKILL: ").append(name).append(" =====\n")
                    .append(body.trim()).append("\n\n");
        }
        return sb.toString();
    }

    /** Highest-priority skill with this name, or null. */
    private AgentSkill find(String name) {
        // Repositories are ordered low priority first, so scan in reverse to honour overrides.
        for (int i = repositories.size() - 1; i >= 0; i--) {
            try {
                AgentSkill skill = repositories.get(i).getSkill(name);
                if (skill != null) {
                    return skill;
                }
            } catch (Exception e) {
                log.warn("skill repository lookup for '{}' failed: {}", name, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Where AgentScope materialises skill files.
     *
     * <p>Always inside the task sandbox. Leaving this null is measurably worse: the middleware
     * mkdtemps {@code agentscope-skill-workdir-*} under the system temp directory for every
     * agent, and since this service builds one agent per task that leaks a directory per task
     * even when nothing is uploaded into it.
     *
     * @param taskDir the task sandbox
     * @return a subdirectory of the task sandbox, cleaned up with it
     */
    public Path workDirFor(Path taskDir) {
        Path dir = taskDir.resolve(".skillwork");
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            log.warn("could not create skill work dir {}: {}", dir, e.getMessage());
            return null;
        }
        return dir;
    }

    private List<AgentSkillRepository> build() {
        AgentProperties.Skills cfg = props.getSkills();
        List<AgentSkillRepository> repos = new ArrayList<>(2);

        // Low priority: skills shipped inside the jar. Absent is normal, not an error.
        String location = cfg.getClasspathLocation();
        if (location != null && !location.isBlank()) {
            try {
                ClasspathSkillRepository bundled = new ClasspathSkillRepository(location.trim());
                repos.add(bundled);
                log.info("bundled skills mounted location={} count={}",
                        location.trim(), safeCount(bundled));
            } catch (Exception e) {
                log.info("no bundled skills under classpath:{} ({})", location.trim(),
                        e.getClass().getSimpleName());
            }
        }

        // High priority: operator directory, so a deployment can add or patch a skill
        // without rebuilding the image. Read-only: this service must never write skills.
        String directory = cfg.getDirectory();
        if (directory != null && !directory.isBlank()) {
            Path dir = Paths.get(directory.trim()).toAbsolutePath().normalize();
            try {
                Files.createDirectories(dir);
                FileSystemSkillRepository external =
                        new FileSystemSkillRepository(dir, false, null, cfg.isLazy());
                repos.add(external);
                log.info("directory skills mounted dir={} lazy={} count={}",
                        dir, cfg.isLazy(), safeCount(external));
            } catch (Exception e) {
                log.error("directory skills unavailable at {}: {} ({})",
                        dir, e.getMessage(), e.getClass().getSimpleName());
            }
        }
        return List.copyOf(repos);
    }

    private static int safeCount(AgentSkillRepository repo) {
        try {
            List<String> names = repo.getAllSkillNames();
            return names == null ? 0 : names.size();
        } catch (Exception e) {
            return 0;
        }
    }
}
