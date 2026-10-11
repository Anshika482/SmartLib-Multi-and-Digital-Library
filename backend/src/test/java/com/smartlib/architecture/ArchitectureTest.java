package com.smartlib.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartlib.security.LibraryAccessGuard;
import com.smartlib.security.LibraryScope;
import com.smartlib.security.SmartLibPrincipal;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;

/** Structural rules that keep library isolation and basic hygiene from eroding as the code grows. */
@AnalyzeClasses(packages = "com.smartlib", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String LIBRARY_PATH = "/api/manage/libraries/{libraryId}";

    /** Every library-scoped endpoint must receive a LibraryScope, i.e. pass through LibraryAccessGuard. */
    @ArchTest
    static final ArchRule libraryEndpointsTakeALibraryScope = methods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .and(handleLibraryScopedPaths())
            .should(takeALibraryScope());

    @ArchTest
    static final ArchRule onlyTheGuardCreatesLibraryScopes = noClasses()
            .that().doNotHaveFullyQualifiedName(LibraryAccessGuard.class.getName())
            .should().callConstructor(LibraryScope.class, long.class, SmartLibPrincipal.class)
            .because("a LibraryScope is proof that the library-access check has already run");

    @ArchTest
    static final ArchRule noStandardStreams = GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule noJavaUtilLogging = GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule noFieldInjection = GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    private static DescribedPredicate<JavaMethod> handleLibraryScopedPaths() {
        return DescribedPredicate.describe("handle " + LIBRARY_PATH + "/**",
                method -> fullPaths(method).stream().anyMatch(p -> p.startsWith(LIBRARY_PATH)));
    }

    private static ArchCondition<JavaMethod> takeALibraryScope() {
        return new ArchCondition<>("take a LibraryScope parameter") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean scoped = method.getRawParameterTypes().stream().anyMatch(t -> t.isEquivalentTo(LibraryScope.class));
                if (!scoped) {
                    events.add(SimpleConditionEvent.violated(method,
                            method.getFullName() + " handles " + fullPaths(method) + " without a LibraryScope parameter"));
                }
            }
        };
    }

    private static List<String> fullPaths(JavaMethod method) {
        Method m = method.reflect();
        RequestMapping onMethod = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
        if (onMethod == null) {
            return List.of();
        }
        RequestMapping onClass = AnnotatedElementUtils.findMergedAnnotation(m.getDeclaringClass(), RequestMapping.class);
        String[] prefixes = onClass == null || onClass.path().length == 0 ? new String[] {""} : onClass.path();
        String[] suffixes = onMethod.path().length == 0 ? new String[] {""} : onMethod.path();
        List<String> paths = new ArrayList<>();
        for (String prefix : prefixes) {
            for (String suffix : suffixes) {
                paths.add(prefix + suffix);
            }
        }
        return paths;
    }
}
