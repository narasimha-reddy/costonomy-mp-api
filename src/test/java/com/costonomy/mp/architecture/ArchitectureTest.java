package com.costonomy.mp.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.freeze.FreezingArchRule.freeze;

/**
 * The modular-monolith rules from CLAUDE.md, as tests: a module owns its entities,
 * repositories, services and controllers, and modules talk through services.
 *
 * Rules that already hold are strict. Rules the code does not yet satisfy are frozen:
 * today's violations are recorded in {@code src/test/resources/archunit_store} and
 * tolerated, but a new one fails the build. Fixing a violation and deleting its line
 * from the store ratchets the codebase forward.
 */
class ArchitectureTest {

    private static final String ROOT = "com.costonomy.mp";
    private static final Pattern MODULE_OF = Pattern.compile("^com\\.costonomy\\.mp\\.([a-z]+)\\.");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    @DisplayName("domain classes depend on neither services, web nor repositories")
    void domainIsInnermost() {
        noClasses().that().resideInAPackage("..mp.*.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..mp.*.service..", "..mp.*.web..", "..mp.*.repository..")
                .check(classes);
    }

    @Test
    @DisplayName("repositories do not depend on services or web")
    void repositoriesDoNotReachUp() {
        noClasses().that().resideInAPackage("..mp.*.repository..")
                .should().dependOnClassesThat().resideInAnyPackage("..mp.*.service..", "..mp.*.web..")
                .check(classes);
    }

    @Test
    @DisplayName("controllers go through services, not repositories")
    void webDoesNotUseRepositories() {
        ArchRule rule = noClasses().that().resideInAPackage("..mp.*.web..")
                .should().dependOnClassesThat().resideInAPackage("..mp.*.repository..");
        freeze(rule).check(classes);
    }

    @Test
    @DisplayName("a module's repositories are used only by that module")
    void repositoriesBelongToTheirModule() {
        ArchRule rule = classes().that().resideInAPackage("..mp.*.repository..")
                .should(onlyBeAccessedFromOwnModule());
        freeze(rule).check(classes);
    }

    private static ArchCondition<JavaClass> onlyBeAccessedFromOwnModule() {
        return new ArchCondition<>("only be accessed from their own module") {
            @Override
            public void check(JavaClass repository, ConditionEvents events) {
                String owner = moduleOf(repository.getName());
                repository.getDirectDependenciesToSelf().forEach(dep -> {
                    JavaClass caller = dep.getOriginClass();
                    String callerModule = moduleOf(caller.getName());
                    if (owner != null && callerModule != null && !owner.equals(callerModule)) {
                        events.add(SimpleConditionEvent.violated(dep,
                                dep.getDescription() + " [" + callerModule + " -> " + owner + " repository]"));
                    }
                });
            }
        };
    }

    private static String moduleOf(String className) {
        Matcher m = MODULE_OF.matcher(className);
        return m.find() ? m.group(1) : null;
    }
}
