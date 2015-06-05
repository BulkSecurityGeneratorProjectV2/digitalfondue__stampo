/**
 * Copyright (C) 2015 digitalfondue (info@digitalfondue.ch)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ch.digitalfondue.stampo.processor;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import ch.digitalfondue.stampo.PathUtils;
import ch.digitalfondue.stampo.StampoGlobalConfiguration;
import ch.digitalfondue.stampo.resource.Directory;
import ch.digitalfondue.stampo.resource.DirectoryResource;
import ch.digitalfondue.stampo.resource.FileResource;
import ch.digitalfondue.stampo.resource.FileResourceWithMetadataSection;
import ch.digitalfondue.stampo.resource.LocaleAwareDirectory;
import ch.digitalfondue.stampo.resource.ResourceFactory;
import ch.digitalfondue.stampo.resource.RootResource;
import ch.digitalfondue.stampo.taxonomy.Taxonomy;

public class IncludeAllPaginator implements Directive {
  
  private static Comparator<FileOrDir> FILE_OR_DIR_COMPARATOR = Comparator.comparing(FileOrDir::getName, new AlphaNumericStringComparator(Locale.ENGLISH));

  private final Directory root;
  private final StampoGlobalConfiguration configuration;
  private final Function<FileResource, Path> outputPathExtractor;
  private final Function<Locale, BiFunction<FileResource, Map<String, Object>, FileResourceProcessorOutput>> resourceProcessor;
  private final ResourceFactory resourceFactory;
  private final Taxonomy taxonomy;

  public IncludeAllPaginator(
      Directory root,
      StampoGlobalConfiguration configuration,
      Function<FileResource, Path> outputPathExtractor,
      Function<Locale, BiFunction<FileResource, Map<String, Object>, FileResourceProcessorOutput>> resourceProcessor,
      Taxonomy taxonomy) {
    this.root = root;
    this.configuration = configuration;
    this.outputPathExtractor = outputPathExtractor;
    this.resourceProcessor = resourceProcessor;
    this.resourceFactory = new ResourceFactory(DirectoryResource::new, FileResourceWithMetadataSection::new, Comparator.comparing(FileResource::getPath), configuration);
    this.taxonomy = taxonomy;
  }

  @Override
  public String name() {
    return "include-all";
  }

  @Override
  public List<PathAndModelSupplier> generateOutputPaths(FileResource resource, Locale locale,
      Path defaultOutputPath) {

    String path = ofNullable(resource.getMetadata().getRawMap().get("include-all")).map(String.class::cast)
            .orElseThrow(IllegalArgumentException::new);

    Path baseDirectory = configuration.getBaseDirectory();
    Path includeAllBasePath = baseDirectory.resolve(path);
    if (!includeAllBasePath.startsWith(baseDirectory)) {
      throw new IllegalArgumentException(includeAllBasePath
          + " must be inside of the basedirectory: " + baseDirectory);// cannot be outside
    }

    final Directory toIncludeAllDir;

    if (configuration.getLocales().size() > 1) {
      toIncludeAllDir = new LocaleAwareDirectory(locale, new RootResource(resourceFactory, includeAllBasePath), FileResourceWithMetadataSection::new);
    } else {
      toIncludeAllDir = new RootResource(resourceFactory, includeAllBasePath);
    }

    int maxDepth = (Integer) resource.getMetadata().getRawMap().getOrDefault("paginate-at-depth", 1);


    List<IncludeAllPage> pages = flattenAndGroupRecursively(toIncludeAllDir, maxDepth, 1);
    
    List<IncludeAllPage> pagesWithDepth0Handled = handleDepth0Page(pages, resource, maxDepth, defaultOutputPath);
    
    List<IncludeAllPageWithOutput> pagesWithOutput = pagesWithDepth0Handled.stream()
            .map(iap -> addOutputInformation(iap, defaultOutputPath, includeAllBasePath, locale))
            .collect(Collectors.toList());
    
    List<IncludeAllPageWithPagination> pagesWithPagination = addPaginationInformation(pagesWithOutput);
    return pagesWithPagination.stream().map(fl -> toPathAndModuleSupplier(fl, locale)).collect(Collectors.toList());
  }

  /*
   * From a given directory, it will flatten the file hierarchy in a list of "pages" (That can contain more than one FileResource)
   */
  private List<IncludeAllPage> flattenAndGroupRecursively(Directory dir, int maxDepth, int depth) {

    List<FileOrDir> fileOrDirs = new ArrayList<>();

    Set<String> pairedDirectories = new HashSet<>();

    Map<String, Directory> childDirs = dir.getDirectories();
    Map<String, FileResource> childFiles = dir.getFiles();

    for (FileResource fr : childFiles.values()) {
      String fileNameWithoutExt = fr.getFileNameWithoutExtensions();
      Optional<Directory> pairedDir = childDirs.containsKey(fileNameWithoutExt) ? of(childDirs.get(fileNameWithoutExt)) : empty();
      fileOrDirs.add(new FileOrDir(of(fr), pairedDir));
      
      pairedDir.ifPresent(d -> {
        pairedDirectories.add(d.getName());
      });
    }

    fileOrDirs.addAll(childDirs.values().stream()
        .filter(d -> !pairedDirectories.contains(d.getName()))
        .map(d -> new FileOrDir(empty(), of(d))).collect(Collectors.toList()));

    fileOrDirs.sort(FILE_OR_DIR_COMPARATOR);


    List<IncludeAllPage> frs = new ArrayList<>();
    for (FileOrDir fd : fileOrDirs) {
      if (depth > maxDepth) {

        if (frs.isEmpty()) {
          frs.add(new IncludeAllPage(depth, new ArrayList<>()));
        }
        IncludeAllPage singleFile = frs.get(0);
        fd.file.ifPresent(singleFile.files::add);
        fd.dir.ifPresent(d -> flattenAndGroupRecursively(d, maxDepth, depth + 1).forEach(iap -> singleFile.files.addAll(iap.files)));
        
      } else {
        IncludeAllPage fileRes = new IncludeAllPage(depth, new ArrayList<>());
        frs.add(fileRes);
        fd.file.ifPresent(fileRes.files::add);

        List<IncludeAllPage> pairedFiles =
            fd.dir.map(d -> flattenAndGroupRecursively(d, maxDepth, depth + 1)).orElse(Collections.emptyList());
        if (depth >= maxDepth) {
          pairedFiles.forEach(iap -> fileRes.files.addAll(iap.files));
        } else {
          pairedFiles.forEach(frs::add);
        }
      }
    }
    return frs;
  }

  /*
   * It's a special case: depth 0 represent the page with the include-all directive. We let the user
   * decide if he want to render it (default: yes). Additionally we handle the case where maxDepth
   * is 0, we should transfer the IncludeAllPage object under the page that have the include-all
   * directive.
   */
  private List<IncludeAllPage> handleDepth0Page(List<IncludeAllPage> pages, FileResource resource, int maxDepth, Path defaultOutputPath) {
    
    boolean skipDepth0 = (boolean) resource.getMetadata().getRawMap().getOrDefault("ignore-depth-0-page", false);

    if (maxDepth == 0) {
      List<FileResource> files = new ArrayList<FileResource>();
      files.add(new FileResourcePlaceHolder(defaultOutputPath, configuration));
      files.addAll(pages.get(0).files);// we know that it has size 1
      return Collections.singletonList(new IncludeAllPage(0, files));
      
    } else if (!skipDepth0) {
      pages.add(0, new IncludeAllPage(0, Collections.singletonList(new FileResourcePlaceHolder(defaultOutputPath, configuration))));
    }

    return pages;
  }
  
  
  // generate the final output path
  private IncludeAllPageWithOutput addOutputInformation(IncludeAllPage pages, Path defaultBaseOutputPath, Path includeAllBasePath, Locale locale) {

    // depth = 0 is the file that has the include-all directive
    if (pages.depth == 0) {
      return new IncludeAllPageWithOutput(pages, pages.files.get(0), pages.files.get(0).getPath(), locale);
    }

    Path parentDefaultOutputPath = defaultBaseOutputPath.getParent();
    FileResource virtualResource = pages.files.stream().findFirst()
        .map(fr -> new VirtualPathFileResource(parentDefaultOutputPath.resolve(includeAllBasePath.relativize(fr.getPath()).toString()), fr))
        .orElseThrow(IllegalStateException::new);

    Path finalOutputPath = outputPathExtractor.apply(virtualResource).normalize();

    return new IncludeAllPageWithOutput(pages, virtualResource, finalOutputPath, locale);
  }

  /*
   * Augment the objects with pagination information. It's done in a separate step as we need the previous and next page reference. 
   */
  private List<IncludeAllPageWithPagination> addPaginationInformation(List<IncludeAllPageWithOutput> pages) {

    List<Header> globalToc = new ArrayList<>();

    List<IncludeAllPageWithPagination> processedResources = new ArrayList<>();
    for (int i = 0; i < pages.size(); i++) {
      if (!pages.get(i).files.isEmpty()) {

        IncludeAllPageWithOutput current = pages.get(i);
        globalToc.addAll(current.summary);

        String previousPageUrl = null;
        String previousPageTitle = null;
        String nextPageUrl = null;
        String nextPageTitle = null;
        if (i > 0) {
          previousPageUrl = PathUtils.relativePathTo(pages.get(i - 1).outputPath, current.outputPath);
          previousPageTitle = pages.get(i - 1).title.orElse(null);
        }
        if (i < pages.size() - 1) {
          nextPageUrl = PathUtils.relativePathTo(pages.get(i + 1).outputPath, current.outputPath);
          nextPageTitle = pages.get(i + 1).title.orElse(null);
        }

        Pagination pagination = new Pagination(i + 1, pages.size(), current.depth, previousPageUrl, 
            previousPageTitle, nextPageUrl, nextPageTitle, current.title.orElse(null));
        processedResources.add(new IncludeAllPageWithPagination(current, pagination, globalToc));
      }
    }
    return processedResources;
  }
  
  // refactor
  private static String htmlSummary(List<Header> summary, Path path) {
    StringBuilder sb = new StringBuilder();
    int lvl = 0;
    int opened = 0;
    for (Header h : summary) {
      if (h.level > lvl) {
        opened++;
        sb.append("<ol>");
        lvl = h.level;
      } else if (h.level < lvl) {

        int diffOpened = lvl - h.level;

        for (int i = 0; i < diffOpened; i++) {
          sb.append("</ol>");
          opened--;
        }
        lvl = h.level;
      }
      sb.append("<li>").append("<a href=\"").append(PathUtils.relativePathTo(h.outputPath, path))
          .append("#").append(h.id).append("\">").append(h.name).append("</a>");
    }

    for (int i = 0; i < opened; i++) {
      sb.append("</ol>");
    }

    return sb.toString();
  }


  private PathAndModelSupplier toPathAndModuleSupplier(IncludeAllPageWithPagination page, Locale locale) {

    Supplier<Map<String, Object>> supplier = () -> {
          Map<String, Object> additionalModel = new HashMap<>();
          additionalModel.put("includeAllResult", page.page.content());
          additionalModel.put("pagination", page.pagination);
          additionalModel.put("summary", htmlSummary(page.page.summary, page.page.outputPath));
          additionalModel.put("globalToc", htmlSummary(page.globalToc, page.page.outputPath));
          return ModelPreparer.prepare(root, configuration, locale, page.page.virtualResource,
              page.page.outputPath, taxonomy, additionalModel);
        };
    return new PathAndModelSupplier(page.page.outputPath, supplier);
  }

  private static class FileOrDir {
    final Optional<FileResource> file;
    final Optional<Directory> dir;
    final String name;

    FileOrDir(Optional<FileResource> file, Optional<Directory> dir) {
      this.file = file;
      this.dir = dir;
      // we are sure that at least one of the two is present
      this.name = file.map(FileResource::getFileNameWithoutExtensions).orElseGet(() -> dir.get().getName());
    }

    String getName() {
      return name;
    }
  }

  private static class IncludeAllPage {
    final int depth;
    final List<FileResource> files;

    IncludeAllPage(int depth, List<FileResource> files) {
      this.depth = depth;
      this.files = files;
    }
  }

  private class IncludeAllPageWithOutput extends IncludeAllPage {

    final FileResource virtualResource;
    final Path outputPath;
    final Locale locale;
    final Optional<String> title;
    final List<Header> summary;


    IncludeAllPageWithOutput(IncludeAllPage includeAllPage, FileResource virtualResource,
        Path outputPath, Locale locale) {
      super(includeAllPage.depth, includeAllPage.files);
      this.virtualResource = virtualResource;
      this.outputPath = outputPath;
      this.locale = locale;

      Elements titles = Jsoup.parseBodyFragment(content()).select("h1,h2,h3,h4,h5,h6");
      this.title = titles.stream().findFirst().map(Element::text);
      this.summary = titles.stream().map(e -> new Header(headerLevel(e.tagName()), e.text(), e.getElementsByTag("a").attr("name"), outputPath)).collect(Collectors.toList());
    }

    int headerLevel(String name) {
      return Integer.parseInt(name.substring(1));
    }

    String content() {
      Map<String, Object> modelForIncludeAllPage = ModelPreparer.prepare(root, configuration, locale, virtualResource, outputPath, taxonomy);
      return files.stream()
          .map(f -> resourceProcessor.apply(locale).apply(f, modelForIncludeAllPage))
          .map(FileResourceProcessorOutput::getContent).collect(Collectors.joining());
    }
  }

  private static class IncludeAllPageWithPagination {
    final IncludeAllPageWithOutput page;
    final Pagination pagination;
    final List<Header> globalToc;

    IncludeAllPageWithPagination(IncludeAllPageWithOutput page, Pagination pagination,
        List<Header> globalToc) {
      this.page = page;
      this.pagination = pagination;
      this.globalToc = globalToc;
    }
  }

  static class Header {
    final int level;
    final String name;
    final String id;
    final Path outputPath;

    Header(int level, String name, String id, Path outputPath) {
      this.level = level;
      this.name = name;
      this.id = id;
      this.outputPath = outputPath;
    }
  }

  public static class Pagination {
    private final int page;
    private final int total;
    private final int depth;
    private final String previousPageUrl;
    private final String previousPageTitle;
    private final String nextPageUrl;
    private final String nextPageTitle;
    private final String pageTitle;

    public Pagination(int page, int total, int depth, String previousPageUrl,
        String previousPageTitle, String nextPageUrl, String nextPageTitle, String pageTitle) {
      this.page = page;
      this.total = total;
      this.depth = depth;
      this.previousPageUrl = previousPageUrl;
      this.previousPageTitle = previousPageTitle;
      this.nextPageUrl = nextPageUrl;
      this.nextPageTitle = nextPageTitle;
      this.pageTitle = pageTitle;
    }

    public int getPage() {
      return page;
    }

    public int getTotal() {
      return total;
    }

    public int getDepth() {
      return depth;
    }

    public String getPreviousPageUrl() {
      return previousPageUrl;
    }

    public String getNextPageUrl() {
      return nextPageUrl;
    }

    public String getPreviousPageTitle() {
      return previousPageTitle;
    }

    public String getNextPageTitle() {
      return nextPageTitle;
    }

    public String getPageTitle() {
      return pageTitle;
    }
  }
}
