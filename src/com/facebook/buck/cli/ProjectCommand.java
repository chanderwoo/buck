/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cli;

import com.facebook.buck.apple.AppleBinaryDescription;
import com.facebook.buck.apple.AppleBuildRules;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.AppleTestDescription;
import com.facebook.buck.apple.ProjectGenerator;
import com.facebook.buck.apple.SchemeActionType;
import com.facebook.buck.apple.WorkspaceAndProjectGenerator;
import com.facebook.buck.apple.XcodeWorkspaceConfigDescription;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.ProjectGenerationEvent;
import com.facebook.buck.halide.HalideBuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaFileParser;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.intellij.IjModuleGraph;
import com.facebook.buck.jvm.java.intellij.IjProject;
import com.facebook.buck.jvm.java.intellij.IntellijConfig;
import com.facebook.buck.jvm.java.intellij.Project;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.FilesystemBackedBuildFileTree;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.parser.BuildFileSpec;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.parser.TargetNodePredicateSpec;
import com.facebook.buck.python.PythonBuckConfig;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.AssociatedTargetNodePredicate;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.ProjectConfig;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndTargets;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.ProcessManager;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.google.common.util.concurrent.ListeningExecutorService;

import org.kohsuke.args4j.Option;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

public class ProjectCommand extends BuildCommand {

  private static final Logger LOG = Logger.get(ProjectCommand.class);

  /**
   * Include java library targets (and android library targets) that use annotation
   * processing.  The sources generated by these annotation processors is needed by
   * IntelliJ.
   */
  private static final Predicate<TargetNode<?>> ANNOTATION_PREDICATE =
      new Predicate<TargetNode<?>>() {
        @Override
        public boolean apply(TargetNode<?> input) {
          return isTargetWithAnnotations(input);
        }
      };

  private static final String XCODE_PROCESS_NAME = "Xcode";

  public enum Ide {
    INTELLIJ,
    XCODE;

    public static Ide fromString(String string) {
      switch (Ascii.toLowerCase(string)) {
        case "intellij":
          return Ide.INTELLIJ;
        case "xcode":
          return Ide.XCODE;
        default:
          throw new HumanReadableException("Invalid ide value %s.", string);
      }
    }

  }

  private static final boolean DEFAULT_READ_ONLY_VALUE = false;
  private static final boolean DEFAULT_DISABLE_R_JAVA_IDEA_GENERATOR = false;

  @Option(
      name = "--combined-project",
      usage = "Generate an xcode project of a target and its dependencies.")
  private boolean combinedProject;

  @Option(
      name = "--build-with-buck",
      usage = "Use Buck to build the generated project instead of delegating the build to the IDE.")
  private boolean buildWithBuck;

  @Option(name = "--process-annotations", usage = "Enable annotation processing")
  private boolean processAnnotations;

  @Option(
      name = "--without-tests",
      usage = "When generating a project slice, exclude tests that test the code in that slice")
  private boolean withoutTests = false;

  @Option(
      name = "--without-dependencies-tests",
      usage = "When generating a project slice, includes tests that test code in main target, " +
          "but exclude tests that test dependencies")
  private boolean withoutDependenciesTests = false;

  @Option(
      name = "--combine-test-bundles",
      usage = "Combine multiple ios/osx test targets into the same bundle if they have identical " +
          "settings")
  private boolean combineTestBundles = false;

  @Option(
      name = "--ide",
      usage = "The type of IDE for which to generate a project. You may specify it in the " +
          ".buckconfig file. Please refer to https://buckbuild.com/concept/buckconfig.html#project")
  @Nullable
  private Ide ide = null;

  @Option(
      name = "--read-only",
      usage = "If true, generate project files read-only. Defaults to '" +
          DEFAULT_READ_ONLY_VALUE + "' if not specified in .buckconfig. (Only " +
          "applies to generated Xcode projects.)")
  private boolean readOnly = DEFAULT_READ_ONLY_VALUE;

  @Option(
      name = "--dry-run",
      usage = "Instead of actually generating the project, only print out the targets that " +
          "would be included.")
  private boolean dryRun = false;

  @Option(
      name = "--disable-r-java-idea-generator",
      usage = "Turn off auto generation of R.java by Android IDEA plugin." +
          " You can specify disable_r_java_idea_generator = true" +
          " in .buckconfig/project section")
  private boolean androidAutoGenerateDisabled = DEFAULT_DISABLE_R_JAVA_IDEA_GENERATOR;

  @Option(
      name = "--experimental-ij-generation",
      usage = "Enables the experimental IntelliJ project generator.")
  private boolean experimentalIntelliJProjectGenerationEnabled = false;

  @Option(
      name = "--intellij-aggregation-mode",
      usage = "Changes how modules are aggregated. Valid options are 'none' (no aggregation), " +
          "'shallow' (no more than 3 levels deep) and 'auto' (based on project size). Defaults " +
          "to 'auto' if not specified in .buckconfig.")
  @Nullable
  private IjModuleGraph.AggregationMode intellijAggregationMode = null;

  @Option(
      name = "--run-ij-cleaner",
      usage = "After generating an IntelliJ project using --experimental-ij-generation, start a " +
          "cleaner which removes any .iml files which weren't generated as part of the project.")
  private boolean runIjCleaner = false;

  public boolean getCombinedProject() {
    return combinedProject;
  }

  public boolean getDryRun() {
    return dryRun;
  }

  public boolean getCombineTestBundles() {
    return combineTestBundles;
  }

  public boolean shouldProcessAnnotations() {
    return processAnnotations;
  }

  public ImmutableMap<Path, String> getBasePathToAliasMap(BuckConfig buckConfig) {
    return buckConfig.getBasePathToAliasMap();
  }

  public JavaPackageFinder getJavaPackageFinder(BuckConfig buckConfig) {
    return buckConfig.createDefaultJavaPackageFinder();
  }

  public Optional<String> getPathToDefaultAndroidManifest(BuckConfig buckConfig) {
    return buckConfig.getValue("project", "default_android_manifest");
  }

  public Optional<String> getPathToPostProcessScript(BuckConfig buckConfig) {
    return buckConfig.getValue("project", "post_process");
  }

  public boolean getReadOnly(BuckConfig buckConfig) {
    if (readOnly) {
      return readOnly;
    }
    return buckConfig.getBooleanValue("project", "read_only", DEFAULT_READ_ONLY_VALUE);
  }

  public boolean isAndroidAutoGenerateDisabled(BuckConfig buckConfig) {
    if (androidAutoGenerateDisabled) {
      return androidAutoGenerateDisabled;
    }
    return buckConfig.getBooleanValue(
            "project",
            "disable_r_java_idea_generator",
            DEFAULT_DISABLE_R_JAVA_IDEA_GENERATOR);
  }

  /**
   * Returns true if Buck should prompt to kill a running IDE before changing its files,
   * false otherwise.
   */
  public boolean getIdePrompt(BuckConfig buckConfig) {
    return buckConfig.getBooleanValue("project", "ide_prompt", true);
  }

  private Optional<Ide> getIdeFromBuckConfig(BuckConfig buckConfig) {
    return buckConfig.getValue("project", "ide").transform(
            new Function<String, Ide>() {
              @Override
              public Ide apply(String input) {
                return Ide.fromString(input);
              }
            });
  }

  public boolean isWithTests() {
    return !withoutTests;
  }

  public boolean isWithDependenciesTests() {
    return !withoutDependenciesTests;
  }

  private List<String> getInitialTargets(BuckConfig buckConfig) {
    Optional<String> initialTargets = buckConfig.getValue("project", "initial_targets");
    return initialTargets.isPresent()
        ? Lists.newArrayList(Splitter.on(' ').trimResults().split(initialTargets.get()))
        : ImmutableList.<String>of();
  }

  public boolean hasInitialTargets(BuckConfig buckConfig) {
    return !getInitialTargets(buckConfig).isEmpty();
  }

  public BuildCommand createBuildCommandOptionsWithInitialTargets(
      BuckConfig buckConfig,
      List<String> additionalInitialTargets) {
    List<String> initialTargets;
    if (additionalInitialTargets.isEmpty()) {
      initialTargets = getInitialTargets(buckConfig);
    } else {
      initialTargets = Lists.newArrayList();
      initialTargets.addAll(getInitialTargets(buckConfig));
      initialTargets.addAll(additionalInitialTargets);
    }

    BuildCommand buildCommand = new BuildCommand(initialTargets);
    return buildCommand;
  }

  public boolean isExperimentalIntelliJProjectGenerationEnabled() {
    return experimentalIntelliJProjectGenerationEnabled;
  }

  public IjModuleGraph.AggregationMode getIntellijAggregationMode(BuckConfig buckConfig) {
    if (intellijAggregationMode != null) {
      return intellijAggregationMode;
    }
    Optional<IjModuleGraph.AggregationMode> aggregationMode =
        buckConfig.getValue("project", "intellij_aggregation_mode")
        .transform(IjModuleGraph.AggregationMode.fromStringFunction());
    return aggregationMode.or(IjModuleGraph.AggregationMode.NONE);
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    Ide projectIde = getIdeFromBuckConfig(params.getBuckConfig()).orNull();
    boolean needsFullRecursiveParse = !isExperimentalIntelliJProjectGenerationEnabled() &&
        projectIde != Ide.XCODE;

    try (CommandThreadManager pool = new CommandThreadManager(
        "Project",
        params.getBuckConfig().getWorkQueueExecutionOrder(),
        getConcurrencyLimit(params.getBuckConfig()))) {
      ImmutableSet<BuildTarget> passedInTargetsSet;
      TargetGraph projectGraph;
      try {
        passedInTargetsSet = params.getParser()
            .resolveTargetSpecs(
                params.getBuckEventBus(),
                params.getCell(),
                getEnableProfiling(),
                pool.getExecutor(),
                parseArgumentsAsTargetNodeSpecs(
                    params.getBuckConfig(),
                    getArguments()),
                SpeculativeParsing.of(true));
        needsFullRecursiveParse = needsFullRecursiveParse || passedInTargetsSet.isEmpty();
        projectGraph = getProjectGraphForIde(
            params,
            pool.getExecutor(),
            passedInTargetsSet,
            needsFullRecursiveParse);
      } catch (BuildTargetException | BuildFileParseException | HumanReadableException e) {
        params.getBuckEventBus().post(ConsoleEvent.severe(
            MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
        return 1;
      }

      projectIde = getIdeBasedOnPassedInTargetsAndProjectGraph(
          params.getBuckConfig(),
          passedInTargetsSet,
          Optional.of(projectGraph));
      if (projectIde == ProjectCommand.Ide.XCODE) {
        checkForAndKillXcodeIfRunning(params, getIdePrompt(params.getBuckConfig()));
      }

      ProjectPredicates projectPredicates = ProjectPredicates.forIde(projectIde);

      ImmutableSet<BuildTarget> graphRoots;
      if (!passedInTargetsSet.isEmpty()) {
        ImmutableSet<BuildTarget> supplementalGraphRoots = ImmutableSet.of();
        if (projectIde == Ide.INTELLIJ && needsFullRecursiveParse) {
          supplementalGraphRoots = getRootBuildTargetsForIntelliJ(
              projectIde,
              projectGraph,
              projectPredicates);
        }
        graphRoots = Sets.union(passedInTargetsSet, supplementalGraphRoots).immutableCopy();
      } else {
        graphRoots = getRootsFromPredicate(
            projectGraph,
            projectPredicates.getProjectRootsPredicate());
      }

      TargetGraphAndTargets targetGraphAndTargets;
      try {
        targetGraphAndTargets = createTargetGraph(
            params,
            projectGraph,
            graphRoots,
            projectPredicates.getAssociatedProjectPredicate(),
            isWithTests(),
            isWithDependenciesTests(),
            needsFullRecursiveParse,
            pool.getExecutor());
      } catch (BuildFileParseException |
          TargetGraph.NoSuchNodeException |
          BuildTargetException |
          HumanReadableException e) {
        params.getBuckEventBus().post(ConsoleEvent.severe(
            MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
        return 1;
      }

      if (getDryRun()) {
        for (TargetNode<?> targetNode : targetGraphAndTargets.getTargetGraph().getNodes()) {
          params.getConsole().getStdOut().println(targetNode.toString());
        }

        return 0;
      }

      params.getBuckEventBus().post(ProjectGenerationEvent.started());
      int result;
      try {
        switch (projectIde) {
          case INTELLIJ:
            result = runIntellijProjectGenerator(
                params,
                projectGraph,
                targetGraphAndTargets,
                passedInTargetsSet);
            break;
          case XCODE:
            result = runXcodeProjectGenerator(
                params,
                targetGraphAndTargets,
                passedInTargetsSet);
            break;
          default:
            // unreachable
            throw new IllegalStateException("'ide' should always be of type 'INTELLIJ' or 'XCODE'");
        }
      } finally {
        params.getBuckEventBus().post(ProjectGenerationEvent.finished());
      }

      return result;
    }
  }

  private Ide getIdeBasedOnPassedInTargetsAndProjectGraph(
      BuckConfig buckConfig,
      ImmutableSet<BuildTarget> passedInTargetsSet,
      Optional<TargetGraph> projectGraph) {
    if (ide != null) {
      return ide;
    }
    Ide projectIde = getIdeFromBuckConfig(buckConfig).orNull();
    if (projectIde == null && !passedInTargetsSet.isEmpty() && projectGraph.isPresent()) {
      Ide guessedIde = null;
      for (BuildTarget buildTarget : passedInTargetsSet) {
        Optional<TargetNode<?>> node = projectGraph.get().getOptional(buildTarget);
        if (!node.isPresent()) {
          throw new HumanReadableException("Project graph %s doesn't contain build target " +
              "%s", projectGraph.get(), buildTarget);
        }
        BuildRuleType nodeType = node.get().getType();
        boolean canGenerateXcodeProject = canGenerateImplicitWorkspaceForType(nodeType);
        canGenerateXcodeProject |= nodeType.equals(XcodeWorkspaceConfigDescription.TYPE);
        if (guessedIde == null && canGenerateXcodeProject) {
          guessedIde = Ide.XCODE;
        } else if (guessedIde == Ide.XCODE && !canGenerateXcodeProject ||
            guessedIde == Ide.INTELLIJ && canGenerateXcodeProject) {
          throw new HumanReadableException("Passed targets (%s) contain both Xcode and Idea " +
              "projects.\nCan't choose Ide from this mixed set. " +
              "Please pass only Xcode targets or only Idea targets.", passedInTargetsSet);
        } else {
          guessedIde = Ide.INTELLIJ;
        }
      }
      projectIde = guessedIde;
    }
    if (projectIde == null) {
      throw new HumanReadableException("Please specify ide using --ide option or set ide in " +
          ".buckconfig");
    }
    return projectIde;
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  public static ImmutableSet<BuildTarget> getRootBuildTargetsForIntelliJ(
      ProjectCommand.Ide ide,
      TargetGraph projectGraph,
      ProjectPredicates projectPredicates) {
    if (ide != ProjectCommand.Ide.INTELLIJ) {
      return ImmutableSet.of();
    }
    return getRootsFromPredicate(
        projectGraph,
        Predicates.and(
            new Predicate<TargetNode<?>>() {

              @Override
              public boolean apply(TargetNode<?> input) {
                return input.getBuildTarget() != null &&
                    input.getBuildTarget().getBasePathWithSlash().isEmpty();
              }
            },
            projectPredicates.getProjectRootsPredicate()
        )
    );
  }

  /**
   * Run intellij specific project generation actions.
   */
  int runExperimentalIntellijProjectGenerator(
      CommandRunnerParams params,
      final TargetGraphAndTargets targetGraphAndTargets) throws IOException, InterruptedException {
    TargetGraphToActionGraph targetGraphToActionGraph = new TargetGraphToActionGraph(
        params.getBuckEventBus(),
        new DefaultTargetNodeToBuildRuleTransformer());
    ActionGraphAndResolver result = Preconditions.checkNotNull(
        targetGraphToActionGraph.apply(targetGraphAndTargets.getTargetGraph()));
    BuildRuleResolver ruleResolver = result.getResolver();
    SourcePathResolver sourcePathResolver = new SourcePathResolver(ruleResolver);

    JavacOptions javacOptions = new JavaBuckConfig(params.getBuckConfig())
        .getDefaultJavacOptions();

    IjProject project = new IjProject(
        targetGraphAndTargets,
        getJavaPackageFinder(params.getBuckConfig()),
        JavaFileParser.createJavaFileParser(javacOptions),
        ruleResolver,
        sourcePathResolver,
        params.getCell().getFilesystem(),
        getIntellijAggregationMode(params.getBuckConfig()),
        params.getBuckConfig());

    ImmutableSet<BuildTarget> requiredBuildTargets = project.write(runIjCleaner);

    if (requiredBuildTargets.isEmpty()) {
      return 0;
    }

    return shouldProcessAnnotations() ?
        buildRequiredTargetsWithoutUsingCacheForAnnotatedTargets(
            params,
            targetGraphAndTargets,
            requiredBuildTargets) :
        runBuild(params, requiredBuildTargets);
  }

  private int buildRequiredTargetsWithoutUsingCacheForAnnotatedTargets(
      CommandRunnerParams params,
      final TargetGraphAndTargets targetGraphAndTargets,
      ImmutableSet<BuildTarget> requiredBuildTargets)
      throws IOException, InterruptedException {
    ImmutableSet<BuildTarget> annotatedTargets =
        getTargetsWithAnnotations(
            targetGraphAndTargets.getTargetGraph(),
            requiredBuildTargets);

    ImmutableSet<BuildTarget> unannotatedTargets =
        Sets.difference(requiredBuildTargets, annotatedTargets).immutableCopy();

    int exitCode = runBuild(params, unannotatedTargets);
    if (exitCode != 0) {
      addBuildFailureError(params);
    }

    if (annotatedTargets.isEmpty()) {
      return exitCode;
    }

    int annotationExitCode = runBuild(params, annotatedTargets, true);
    if (exitCode == 0 && annotationExitCode != 0) {
      addBuildFailureError(params);
    }

    return exitCode == 0 ? annotationExitCode : exitCode;
  }

  private int runBuild(
      CommandRunnerParams params,
      ImmutableSet<BuildTarget> targets)
      throws IOException, InterruptedException {
    return runBuild(params, targets, false);
  }

  private int runBuild(
      CommandRunnerParams params,
      ImmutableSet<BuildTarget> targets,
      boolean disableCaching)
      throws IOException, InterruptedException {
    BuildCommand buildCommand = new BuildCommand(FluentIterable.from(targets)
        .transform(Functions.toStringFunction())
        .toList());
    buildCommand.setKeepGoing(true);
    buildCommand.setArtifactCacheDisabled(disableCaching);
    return buildCommand.run(params);
  }


  private ImmutableSet<BuildTarget> getTargetsWithAnnotations(
      final TargetGraph targetGraph,
      ImmutableSet<BuildTarget> buildTargets) {
    return FluentIterable
        .from(buildTargets)
        .filter(new Predicate<BuildTarget>() {
          @Override
          public boolean apply(@Nullable BuildTarget input) {
            TargetNode<?> targetNode = targetGraph.get(input);
            return targetNode != null && isTargetWithAnnotations(targetNode);
          }
        })
        .toSet();
  }

  private void addBuildFailureError(CommandRunnerParams params) {
    params.getConsole().getAnsi().printHighlightedSuccessText(
        params.getConsole().getStdErr(),
        "Because the build did not complete successfully some parts of the project may not\n" +
            "work correctly with IntelliJ. Please fix the errors and run this command again.\n");
  }


  /**
   * Run intellij specific project generation actions.
   */
  int runIntellijProjectGenerator(
      CommandRunnerParams params,
      TargetGraph projectGraph,
      TargetGraphAndTargets targetGraphAndTargets,
      ImmutableSet<BuildTarget> passedInTargetsSet)
      throws IOException, InterruptedException {
    if (isExperimentalIntelliJProjectGenerationEnabled()) {
      return runExperimentalIntellijProjectGenerator(params, targetGraphAndTargets);
    }
    // Create an ActionGraph that only contains targets that can be represented as IDE
    // configuration files.
    ActionGraphAndResolver result = Preconditions.checkNotNull(
        new TargetGraphToActionGraph(
            params.getBuckEventBus(),
            new DefaultTargetNodeToBuildRuleTransformer())
            .apply(targetGraphAndTargets.getTargetGraph()));
    ActionGraph actionGraph = result.getActionGraph();

    try (ExecutionContext executionContext = createExecutionContext(params)) {
      Project project = new Project(
          new SourcePathResolver(result.getResolver()),
          FluentIterable
              .from(actionGraph.getNodes())
              .filter(ProjectConfig.class)
              .toSortedSet(Ordering.natural()),
          actionGraph,
          getBasePathToAliasMap(params.getBuckConfig()),
          getJavaPackageFinder(params.getBuckConfig()),
          executionContext,
          new FilesystemBackedBuildFileTree(
              params.getCell().getFilesystem(),
              new ParserConfig(params.getBuckConfig()).getBuildFileName()),
          params.getCell().getFilesystem(),
          getPathToDefaultAndroidManifest(params.getBuckConfig()),
          new IntellijConfig(params.getBuckConfig()),
          getPathToPostProcessScript(params.getBuckConfig()),
          new PythonBuckConfig(
              params.getBuckConfig(),
              new ExecutableFinder()).getPythonInterpreter(),
          params.getObjectMapper(),
          isAndroidAutoGenerateDisabled(params.getBuckConfig()));

      File tempDir = Files.createTempDir();
      File tempFile = new File(tempDir, "project.json");
      int exitCode;
      try {
        exitCode = project.createIntellijProject(
            tempFile,
            executionContext.getProcessExecutor(),
            !passedInTargetsSet.isEmpty(),
            params.getConsole().getStdOut(),
            params.getConsole().getStdErr());
        if (exitCode != 0) {
          return exitCode;
        }

        List<String> additionalInitialTargets = ImmutableList.of();
        if (shouldProcessAnnotations()) {
          try {
            additionalInitialTargets = getAnnotationProcessingTargets(
                projectGraph,
                passedInTargetsSet);
          } catch (BuildTargetException | BuildFileParseException e) {
            throw new HumanReadableException(e);
          }
        }

        // Build initial targets.
        if (hasInitialTargets(params.getBuckConfig()) ||
            !additionalInitialTargets.isEmpty()) {
          BuildCommand buildCommand = createBuildCommandOptionsWithInitialTargets(
              params.getBuckConfig(),
              additionalInitialTargets);

          buildCommand.setArtifactCacheDisabled(true);

          exitCode = buildCommand.runWithoutHelp(params);
          if (exitCode != 0) {
            return exitCode;
          }
        }
      } finally {
        // Either leave project.json around for debugging or delete it on exit.
        if (params.getConsole().getVerbosity().shouldPrintOutput()) {
          params.getConsole().getStdErr().printf(
              "project.json was written to %s",
              tempFile.getAbsolutePath());
        } else {
          tempFile.delete();
          tempDir.delete();
        }
      }

      if (passedInTargetsSet.isEmpty()) {
        String greenStar = params.getConsole().getAnsi().asHighlightedSuccessText(" * ");
        params.getConsole().getStdErr().printf(
            params.getConsole().getAnsi().asHighlightedSuccessText("=== Did you know ===") + "\n" +
                greenStar + "You can run `buck project <target>` to generate a minimal project " +
                "just for that target.\n" +
                greenStar + "This will make your IDE faster when working on large projects.\n" +
                greenStar + "See buck project --help for more info.\n" +
                params.getConsole().getAnsi().asHighlightedSuccessText(
                    "--=* Knowing is half the battle!") + "\n");
      }

      return 0;
    }
  }

  ImmutableList<String> getAnnotationProcessingTargets(
      TargetGraph projectGraph,
      ImmutableSet<BuildTarget> passedInTargetsSet)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    ImmutableSet<BuildTarget> buildTargets;
    if (!passedInTargetsSet.isEmpty()) {
      buildTargets = passedInTargetsSet;
    } else {
      buildTargets = getRootsFromPredicate(
          projectGraph,
          ANNOTATION_PREDICATE);
    }
    return FluentIterable
        .from(buildTargets)
        .transform(Functions.toStringFunction())
        .toList();
  }

  /**
   * Run xcode specific project generation actions.
   */
  int runXcodeProjectGenerator(
      final CommandRunnerParams params,
      final TargetGraphAndTargets targetGraphAndTargets,
      ImmutableSet<BuildTarget> passedInTargetsSet)
      throws IOException, InterruptedException {
    int exitCode = 0;
    AppleConfig appleConfig = new AppleConfig(params.getBuckConfig());
    ImmutableSet<ProjectGenerator.Option> options = buildWorkspaceGeneratorOptions(
        getReadOnly(params.getBuckConfig()),
        isWithTests(),
        isWithDependenciesTests(),
        getCombinedProject(),
        appleConfig.shouldUseHeaderMapsInXcodeProject());

    ImmutableSet<BuildTarget> requiredBuildTargets = generateWorkspacesForTargets(
        params,
        targetGraphAndTargets,
        passedInTargetsSet,
        options,
        super.getOptions(),
        new HashMap<Path, ProjectGenerator>(),
        getCombinedProject(),
        buildWithBuck || shouldForceBuildingWithBuck(params.getBuckConfig(), passedInTargetsSet),
        getCombineTestBundles());
    if (!requiredBuildTargets.isEmpty()) {
      BuildCommand buildCommand = new BuildCommand(FluentIterable.from(requiredBuildTargets)
          .transform(Functions.toStringFunction())
          .toList());
      exitCode = buildCommand.runWithoutHelp(params);
    }
    return exitCode;
  }

  private boolean shouldForceBuildingWithBuck(
      BuckConfig buckConfig,
      ImmutableSet<BuildTarget> passedInTargetsSet) {
    if (passedInTargetsSet.size() == 0) {
      return false;
    }
    ImmutableList<BuildTarget> forcedTargets =
        buckConfig.getBuildTargetList("project", "force_build_with_buck_targets");
    return forcedTargets.containsAll(passedInTargetsSet);
  }

  @VisibleForTesting
  static ImmutableSet<BuildTarget> generateWorkspacesForTargets(
      final CommandRunnerParams params,
      final TargetGraphAndTargets targetGraphAndTargets,
      ImmutableSet<BuildTarget> passedInTargetsSet,
      ImmutableSet<ProjectGenerator.Option> options,
      ImmutableList<String> buildWithBuckFlags,
      Map<Path, ProjectGenerator> projectGenerators,
      boolean combinedProject,
      boolean buildWithBuck,
      boolean combineTestBundles)
      throws IOException, InterruptedException {
    ImmutableSet<BuildTarget> targets;
    if (passedInTargetsSet.isEmpty()) {
      targets = FluentIterable
          .from(targetGraphAndTargets.getProjectRoots())
          .transform(HasBuildTarget.TO_TARGET)
          .toSet();
    } else {
      targets = passedInTargetsSet;
    }

    final Supplier<TargetGraphToActionGraph> targetGraphToActionGraphSupplier = Suppliers.memoize(
        new Supplier<TargetGraphToActionGraph>() {
          @Override
          public TargetGraphToActionGraph get() {
            return new TargetGraphToActionGraph(
                params.getBuckEventBus(),
                new DefaultTargetNodeToBuildRuleTransformer());
          }
        });
    final LoadingCache<TargetNode<?>, SourcePathResolver>
        sourcePathResolverCache =
            CacheBuilder.newBuilder().build(
                new CacheLoader<TargetNode<?>, SourcePathResolver>() {
                  @Override
                  public SourcePathResolver load(TargetNode<?> targetNode) {
                    TargetGraphToActionGraph targetGraphToActionGraph =
                        targetGraphToActionGraphSupplier.get();
                    TargetGraph subgraph = targetGraphAndTargets.getTargetGraph().getSubgraph(
                        ImmutableSet.of(targetNode));
                    BuildRuleResolver buildRuleResolver =
                        targetGraphToActionGraph.apply(subgraph).getResolver();
                    return new SourcePathResolver(buildRuleResolver);
                  }
                });

    LOG.debug("Generating workspace for config targets %s", targets);
    ImmutableSet<TargetNode<?>> testTargetNodes = targetGraphAndTargets.getAssociatedTests();
    ImmutableSet<TargetNode<AppleTestDescription.Arg>> groupableTests = combineTestBundles
        ? AppleBuildRules.filterGroupableTests(testTargetNodes)
        : ImmutableSet.<TargetNode<AppleTestDescription.Arg>>of();
    ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder = ImmutableSet.builder();
    for (final BuildTarget inputTarget : targets) {
      TargetNode<?> inputNode = targetGraphAndTargets.getTargetGraph().get(inputTarget);
      XcodeWorkspaceConfigDescription.Arg workspaceArgs;
      BuildRuleType type = inputNode.getType();
      if (type == XcodeWorkspaceConfigDescription.TYPE) {
        TargetNode<XcodeWorkspaceConfigDescription.Arg> castedWorkspaceNode =
            castToXcodeWorkspaceTargetNode(inputNode);
        workspaceArgs = castedWorkspaceNode.getConstructorArg();
      } else if (canGenerateImplicitWorkspaceForType(type)) {
        workspaceArgs = createImplicitWorkspaceArgs(inputNode);
      } else {
        throw new HumanReadableException(
            "%s must be a xcode_workspace_config, apple_binary, apple_bundle, or apple_library",
            inputNode);
      }

      AppleConfig appleConfig = new AppleConfig(params.getBuckConfig());
      HalideBuckConfig halideBuckConfig = new HalideBuckConfig(params.getBuckConfig());
      CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(params.getBuckConfig());

      CxxPlatform defaultCxxPlatform = params.getCell().getKnownBuildRuleTypes().
          getDefaultCxxPlatforms();
      WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
          params.getCell(),
          targetGraphAndTargets.getTargetGraph(),
          workspaceArgs,
          inputTarget,
          options,
          combinedProject,
          buildWithBuck,
          buildWithBuckFlags,
          !appleConfig.getXcodeDisableParallelizeBuild(),
          appleConfig.shouldAttemptToDetermineBestCxxPlatform(),
          new ExecutableFinder(),
          params.getEnvironment(),
          params.getCell().getKnownBuildRuleTypes().getCxxPlatforms(),
          defaultCxxPlatform,
          new ParserConfig(params.getBuckConfig()).getBuildFileName(),
          new Function<TargetNode<?>, SourcePathResolver>() {
            @Override
            public SourcePathResolver apply(TargetNode<?> input) {
              return sourcePathResolverCache.getUnchecked(input);
            }
          },
          params.getBuckEventBus(),
          halideBuckConfig,
          cxxBuckConfig);
      generator.setGroupableTests(groupableTests);
      generator.generateWorkspaceAndDependentProjects(projectGenerators);
      ImmutableSet<BuildTarget> requiredBuildTargetsForWorkspace =
          generator.getRequiredBuildTargets();
      LOG.debug(
          "Required build targets for workspace %s: %s",
          inputTarget,
          requiredBuildTargetsForWorkspace);
      requiredBuildTargetsBuilder.addAll(requiredBuildTargetsForWorkspace);
    }

    return requiredBuildTargetsBuilder.build();
  }

  public static ImmutableSet<ProjectGenerator.Option> buildWorkspaceGeneratorOptions(
      boolean isReadonly,
      boolean isWithTests,
      boolean isWithDependenciesTests,
      boolean isProjectsCombined,
      boolean shouldUseHeaderMaps) {
    ImmutableSet.Builder<ProjectGenerator.Option> optionsBuilder = ImmutableSet.builder();
    if (isReadonly) {
      optionsBuilder.add(ProjectGenerator.Option.GENERATE_READ_ONLY_FILES);
    }
    if (isWithTests) {
      optionsBuilder.add(ProjectGenerator.Option.INCLUDE_TESTS);
    }
    if (isWithDependenciesTests) {
      optionsBuilder.add(ProjectGenerator.Option.INCLUDE_DEPENDENCIES_TESTS);
    }
    if (isProjectsCombined) {
      optionsBuilder.addAll(ProjectGenerator.COMBINED_PROJECT_OPTIONS);
    } else {
      optionsBuilder.addAll(ProjectGenerator.SEPARATED_PROJECT_OPTIONS);
    }
    if (!shouldUseHeaderMaps) {
      optionsBuilder.add(ProjectGenerator.Option.DISABLE_HEADER_MAPS);
    }
    return optionsBuilder.build();
  }

  @SuppressWarnings(value = "unchecked")
  private static TargetNode<XcodeWorkspaceConfigDescription.Arg> castToXcodeWorkspaceTargetNode(
      TargetNode<?> targetNode) {
    Preconditions.checkArgument(targetNode.getType() == XcodeWorkspaceConfigDescription.TYPE);
    return (TargetNode<XcodeWorkspaceConfigDescription.Arg>) targetNode;
  }

  private void checkForAndKillXcodeIfRunning(CommandRunnerParams params, boolean enablePrompt)
      throws InterruptedException, IOException {
    Optional<ProcessManager> processManager = params.getProcessManager();
    if (!processManager.isPresent()) {
      LOG.warn("Could not check if Xcode is running (no process manager)");
      return;
    }

    if (!processManager.get().isProcessRunning(XCODE_PROCESS_NAME)) {
      LOG.debug("Xcode is not running.");
      return;
    }

    boolean canPromptResult = canPrompt(params.getEnvironment());
    if (enablePrompt && canPromptResult) {
      if (
          prompt(
              params,
              "Xcode is currently running. Buck will modify files Xcode currently has " +
                  "open, which can cause it to become unstable.\n\n" +
                  "Kill Xcode and continue?")) {
        processManager.get().killProcess(XCODE_PROCESS_NAME);
      } else {
        params.getConsole().getStdOut().println(
            params.getConsole().getAnsi().asWarningText(
                "Xcode is running. Generated projects might be lost or corrupted if Xcode " +
                    "currently has them open."));
      }
      params.getConsole().getStdOut().format(
          "To disable this prompt in the future, add the following to %s: \n\n" +
              "[project]\n" +
              "  ide_prompt = false\n\n",
          params.getCell().getFilesystem()
              .getRootPath()
              .resolve(BuckConfig.DEFAULT_BUCK_CONFIG_OVERRIDE_FILE_NAME)
              .toAbsolutePath());
    } else {
      LOG.debug(
          "Xcode is running, but cannot prompt to kill it (enabled %s, can prompt %s)",
          enablePrompt, canPromptResult);
    }
  }

  private boolean canPrompt(ImmutableMap<String, String> environment) {
    String nailgunStdinTty = environment.get("NAILGUN_TTY_0");
    if (nailgunStdinTty != null) {
      return nailgunStdinTty.equals("1");
    } else {
      return System.console() != null;
    }
  }

  private boolean prompt(CommandRunnerParams params, String prompt) throws IOException {
    Preconditions.checkState(canPrompt(params.getEnvironment()));

    LOG.debug("Displaying prompt %s..", prompt);
    params
        .getConsole()
        .getStdOut()
        .print(params.getConsole().getAnsi().asWarningText(prompt + " [Y/n] "));

    Optional<String> result;
    try (InputStreamReader stdinReader = new InputStreamReader(System.in, Charsets.UTF_8);
         BufferedReader bufferedStdinReader = new BufferedReader(stdinReader)) {
      result = Optional.fromNullable(bufferedStdinReader.readLine());
    }
    LOG.debug("Result of prompt: [%s]", result);
    return result.isPresent() &&
        (result.get().isEmpty() || result.get().toLowerCase(Locale.US).startsWith("y"));
  }

  @VisibleForTesting
  static ImmutableSet<BuildTarget> getRootsFromPredicate(
      TargetGraph projectGraph,
      Predicate<TargetNode<?>> rootsPredicate) {
    return FluentIterable
        .from(projectGraph.getNodes())
        .filter(rootsPredicate)
        .transform(HasBuildTarget.TO_TARGET)
        .toSet();
  }

  private TargetGraph getProjectGraphForIde(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      ImmutableSet<BuildTarget> passedInTargets,
      boolean needsFullRecursiveParse
  ) throws InterruptedException, BuildFileParseException, BuildTargetException, IOException {
    if (needsFullRecursiveParse) {
      return params.getParser()
          .buildTargetGraphForTargetNodeSpecs(
              params.getBuckEventBus(),
              params.getCell(),
              getEnableProfiling(),
              executor,
              ImmutableList.of(
                  TargetNodePredicateSpec.of(
                      Predicates.<TargetNode<?>>alwaysTrue(),
                      BuildFileSpec.fromRecursivePath(Paths.get("")))),
              /* ignoreBuckAutodepsFiles */ false)
          .getSecond();
    }
    Preconditions.checkState(!passedInTargets.isEmpty());
    return params.getParser()
        .buildTargetGraph(
            params.getBuckEventBus(),
            params.getCell(),
            getEnableProfiling(),
            executor,
            passedInTargets);

  }

  private TargetGraphAndTargets createTargetGraph(
      CommandRunnerParams params,
      TargetGraph projectGraph,
      ImmutableSet<BuildTarget> graphRoots,
      AssociatedTargetNodePredicate associatedProjectPredicate,
      boolean isWithTests,
      boolean isWithDependenciesTests,
      boolean needsFullRecursiveParse,
      ListeningExecutorService executor
  )
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException {

    ImmutableSet<BuildTarget> explicitTestTargets = ImmutableSet.of();
    ImmutableSet<BuildTarget> graphRootsOrSourceTargets =
        replaceWorkspacesWithSourceTargetsIfPossible(graphRoots, projectGraph);

    if (isWithTests) {
      explicitTestTargets = TargetGraphAndTargets.getExplicitTestTargets(
          graphRootsOrSourceTargets,
          projectGraph,
          isWithDependenciesTests);
      // The test nodes for a recursively parsed project is the same regardless.
      if (!needsFullRecursiveParse) {
        projectGraph = params.getParser().buildTargetGraph(
            params.getBuckEventBus(),
            params.getCell(),
            getEnableProfiling(),
            executor,
            Sets.union(graphRoots, explicitTestTargets));
      }
    }

    return TargetGraphAndTargets.create(
        graphRoots,
        graphRootsOrSourceTargets,
        projectGraph,
        associatedProjectPredicate,
        isWithTests,
        isWithDependenciesTests,
        explicitTestTargets);
  }

  public static ImmutableSet<BuildTarget> replaceWorkspacesWithSourceTargetsIfPossible(
      ImmutableSet<BuildTarget> buildTargets, TargetGraph projectGraph) {
    Iterable<TargetNode<?>> targetNodes = projectGraph.getAll(buildTargets);
    ImmutableSet.Builder<BuildTarget> resultBuilder = ImmutableSet.builder();
    for (TargetNode<?> node : targetNodes) {
      BuildRuleType type = node.getType();
      if (type == XcodeWorkspaceConfigDescription.TYPE) {
        TargetNode<XcodeWorkspaceConfigDescription.Arg> castedWorkspaceNode =
            castToXcodeWorkspaceTargetNode(node);
        Optional<BuildTarget> srcTarget = castedWorkspaceNode.getConstructorArg().srcTarget;
        if (srcTarget.isPresent()) {
          resultBuilder.add(srcTarget.get());
        } else {
          resultBuilder.add(node.getBuildTarget());
        }
      } else {
        resultBuilder.add(node.getBuildTarget());
      }
    }
    return resultBuilder.build();
  }

  private static boolean canGenerateImplicitWorkspaceForType(BuildRuleType type) {
    // We weren't given a workspace target, but we may have been given something that could
    // still turn into a workspace (for example, a library or an actual app rule). If that's the
    // case we still want to generate a workspace.
    return type == AppleBinaryDescription.TYPE ||
        type == AppleBundleDescription.TYPE ||
        type == AppleLibraryDescription.TYPE;
  }

  /**
   * @param sourceTargetNode - The TargetNode which will act as our fake workspaces `src_target`
   * @return Workspace Args that describe a generic Xcode workspace containing `src_target` and its
   * tests
   */
  private static XcodeWorkspaceConfigDescription.Arg createImplicitWorkspaceArgs(
      TargetNode<?> sourceTargetNode) {
    XcodeWorkspaceConfigDescription.Arg workspaceArgs = new XcodeWorkspaceConfigDescription.Arg();
    workspaceArgs.srcTarget = Optional.of(sourceTargetNode.getBuildTarget());
    workspaceArgs.actionConfigNames = Optional.of(ImmutableMap.<SchemeActionType, String>of());
    workspaceArgs.extraTests = Optional.of(ImmutableSortedSet.<BuildTarget>of());
    workspaceArgs.extraTargets = Optional.of(ImmutableSortedSet.<BuildTarget>of());
    workspaceArgs.workspaceName = Optional.absent();
    workspaceArgs.extraSchemes = Optional.of(ImmutableSortedMap.<String, BuildTarget>of());
    workspaceArgs.isRemoteRunnable = Optional.absent();
    return workspaceArgs;
  }

  private static boolean isTargetWithAnnotations(TargetNode<?> target) {
    if (target.getType() != JavaLibraryDescription.TYPE) {
      return false;
    }
    JavaLibraryDescription.Arg arg = ((JavaLibraryDescription.Arg) target.getConstructorArg());
    return !arg.annotationProcessors.get().isEmpty();
  }

  @Override
  public String getShortDescription() {
    return "generates project configuration files for an IDE";
  }

}
