package org.apache.maven.plugins.shade;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.shade.filter.Filter;

import com.google.common.base.Joiner;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.resource.ManifestResourceTransformer;
import org.apache.maven.plugins.shade.resource.ResourceTransformer;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.RemappingClassAdapter;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipException;

/**
 * @author Jason van Zyl
 */
@Component(role = Shader.class, hint = "default")
public class DefaultShader extends AbstractLogEnabled implements Shader {

  public void shade(ShadeRequest shadeRequest) throws IOException, MojoExecutionException {
    Set<String> resources = new HashSet<String>();

    ResourceTransformer manifestTransformer = null;
    List<ResourceTransformer> transformers = new ArrayList<ResourceTransformer>(shadeRequest.getResourceTransformers());
    for (Iterator<ResourceTransformer> it = transformers.iterator(); it.hasNext();) {
      ResourceTransformer transformer = it.next();
      if (transformer instanceof ManifestResourceTransformer) {
        manifestTransformer = transformer;
        it.remove();
      }
    }

    RelocatorRemapper remapper = new RelocatorRemapper(shadeRequest.getRelocators());

    //noinspection ResultOfMethodCallIgnored
    shadeRequest.getUberJar().getParentFile().mkdirs();
    FileOutputStream fileOutputStream = new FileOutputStream(shadeRequest.getUberJar());
    JarOutputStream jos = new JarOutputStream(new BufferedOutputStream(fileOutputStream));

    if (manifestTransformer != null) {
      for (File file : shadeRequest.getJars()) {
        if(!file.exists()) {
          continue;
        }
        CloseableIterable<ShadeEntry> shadeIterable;
        if (file.isDirectory()) {
          shadeIterable = new DirectoryIterator(file);
        } else {
          shadeIterable = new JarIterator(file);
        }
        for (ShadeEntry entry : shadeIterable) {
          if (entry == null) {
            continue;
          }
          String resource = entry.getName();
          if (manifestTransformer.canTransformResource(resource)) {
            resources.add(entry.getName());
            manifestTransformer.processResource(resource, entry.getInputStream(), shadeRequest.getRelocators());
            break;
          }
        }
      }
      if (manifestTransformer.hasTransformedResource()) {
        manifestTransformer.modifyOutputStream(jos);
      }
    }

    Multimap<String, File> duplicates = HashMultimap.create(10000, 3);

    for (File file : shadeRequest.getJars()) {
      if(!file.exists()) {
        continue;
      }
      CloseableIterable<ShadeEntry> shadeIterable;
      if (file.isDirectory()) {
        shadeIterable = new DirectoryIterator(file);
      } else {
        shadeIterable = new JarIterator(file);
      }

      for (ShadeEntry entry : shadeIterable) {

        if (entry == null) {
          continue;
        }

        getLogger().debug("Processing " + file);

        List<Filter> jarFilters = getFilters(file, shadeRequest.getFilters());

        String name = entry.getName();

        if ("META-INF/INDEX.LIST".equals(name)) {
          // we cannot allow the jar indexes to be copied over or the
          // jar is useless. Ideally, we could create a new one
          // later
          continue;
        }

        if (!entry.isDirectory() && !isFiltered(jarFilters, name)) {
          InputStream is = entry.getInputStream();

          String mappedName = remapper.map(name);

          int idx = mappedName.lastIndexOf('/');
          if (idx != -1) {
            // make sure dirs are created
            String dir = mappedName.substring(0, idx);
            if (!resources.contains(dir)) {
              addDirectory(resources, jos, dir);
            }
          }

          if (name.endsWith(".class")) {
            duplicates.put(name, file);
            addRemappedClass(remapper, jos, file, name, is);
          } else if (shadeRequest.isShadeSourcesContent() && name.endsWith(".java")) {
            // Avoid duplicates
            if (resources.contains(mappedName)) {
              continue;
            }

            addJavaSource(resources, jos, mappedName, is, shadeRequest.getRelocators());
          } else {
            if (!resourceTransformed(transformers, mappedName, is, shadeRequest.getRelocators())) {
              // Avoid duplicates that aren't accounted for by the resource transformers
              if (resources.contains(mappedName)) {
                continue;
              }

              addResource(resources, jos, mappedName, is);
            }
          }

          IOUtil.close(is);
        }

      }

      shadeIterable.close();
    }

    Multimap<Collection<File>, String> overlapping = HashMultimap.create(20, 15);

    for (String clazz : duplicates.keySet()) {
      Collection<File> jarz = duplicates.get(clazz);
      if (jarz.size() > 1) {
        overlapping.put(jarz, clazz);
      }
    }

    // Log a summary of duplicates
    for (Collection<File> jarz : overlapping.keySet()) {
      List<String> jarzS = new LinkedList<String>();

      for (File jjar : jarz)
        jarzS.add(jjar.getName());

      List<String> classes = new LinkedList<String>();

      for (String clazz : overlapping.get(jarz))
        classes.add(clazz.replace(".class", "").replace("/", "."));

      getLogger().warn(Joiner.on(", ").join(jarzS) + " define " + classes.size() + " overlappping classes: ");

      int max = 10;

      for (int i = 0; i < Math.min(max, classes.size()); i++)
        getLogger().warn("  - " + classes.get(i));

      if (classes.size() > max)
        getLogger().warn("  - " + (classes.size() - max) + " more...");

    }

    if (overlapping.keySet().size() > 0) {
      getLogger().warn("maven-shade-plugin has detected that some .class files");
      getLogger().warn("are present in two or more JARs. When this happens, only");
      getLogger().warn("one single version of the class is copied in the uberjar.");
      getLogger().warn("Usually this is not harmful and you can skeep these");
      getLogger().warn("warnings, otherwise try to manually exclude artifacts");
      getLogger().warn("based on mvn dependency:tree -Ddetail=true and the above");
      getLogger().warn("output");
      getLogger().warn("See http://docs.codehaus.org/display/MAVENUSER/Shade+Plugin");
    }

    for (ResourceTransformer transformer : transformers) {
      if (transformer.hasTransformedResource()) {
        transformer.modifyOutputStream(jos);
      }
    }

    IOUtil.close(jos);

    for (Filter filter : shadeRequest.getFilters()) {
      filter.finished();
    }
  }

  class ShadeEntry {
    String name;
    InputStream inputStream;
    boolean directory;

    public ShadeEntry(String name, InputStream inputStream, boolean directory) {
      this.name = name;
      this.inputStream = inputStream;
      this.directory = directory;
    }

    public String getName() {
      return name;
    }

    public InputStream getInputStream() {
      return inputStream;
    }

    public boolean isDirectory() {
      return directory;
    }
  }

  interface CloseableIterable<T> extends Iterable<T> {
    void close() throws IOException;
  }

  class DirectoryIterator implements CloseableIterable<ShadeEntry> {

    Iterator<String> iterator;
    File directory;

    DirectoryIterator(File directory) throws IOException {
      this.directory = directory;
      this.iterator = FileUtils.getFileNames(directory, null, null, false).iterator();
    }

    public Iterator<ShadeEntry> iterator() {
      return new Iterator<ShadeEntry>() {

        public boolean hasNext() {
          return iterator.hasNext();
        }

        public ShadeEntry next() {
          String fileName = iterator.next();
          File f = new File(directory, fileName);
          ShadeEntry entry = null;
          if (f.exists()) {
            try {
              entry = new ShadeEntry(fileName, new FileInputStream(f), false);
            } catch (FileNotFoundException e) {
            }
          }
          return entry;
        }

        public void remove() {
        }
      };
    }

    public void close() throws IOException {
      // do nothing for a directory
    }
  }

  class JarIterator implements CloseableIterable<ShadeEntry> {

    JarFile jarFile;
    Enumeration<JarEntry> enumeration;

    JarIterator(File file) throws IOException {
      jarFile = new JarFile(file);
      enumeration = jarFile.entries();
    }

    public Iterator<ShadeEntry> iterator() {
      return new Iterator<ShadeEntry>() {

        public boolean hasNext() {
          return enumeration.hasMoreElements();
        }

        public ShadeEntry next() {
          JarEntry entry = enumeration.nextElement();
          try {
            return new ShadeEntry(entry.getName(), jarFile.getInputStream(entry), entry.isDirectory());
          } catch (IOException e) {
            return null;
          }
        }

        public void remove() {
        }
      };
    }

    public void close() throws IOException {
      jarFile.close();
    }
  }

  private List<Filter> getFilters(File jar, List<Filter> filters) {
    List<Filter> list = new ArrayList<Filter>();

    for (Filter filter : filters) {
      if (filter.canFilter(jar)) {
        list.add(filter);
      }

    }

    return list;
  }

  private void addDirectory(Set<String> resources, JarOutputStream jos, String name) throws IOException {
    if (name.lastIndexOf('/') > 0) {
      String parent = name.substring(0, name.lastIndexOf('/'));
      if (!resources.contains(parent)) {
        addDirectory(resources, jos, parent);
      }
    }

    // directory entries must end in "/"
    JarEntry entry = new JarEntry(name + "/");
    jos.putNextEntry(entry);

    resources.add(name);
  }

  private void addRemappedClass(RelocatorRemapper remapper, JarOutputStream jos, File jar, String name, InputStream is) throws IOException, MojoExecutionException {
    if (!remapper.hasRelocators()) {
      try {
        jos.putNextEntry(new JarEntry(name));
        IOUtil.copy(is, jos);
      } catch (ZipException e) {
        getLogger().debug("We have a duplicate " + name + " in " + jar);
      }

      return;
    }

    ClassReader cr = new ClassReader(is);

    // We don't pass the ClassReader here. This forces the ClassWriter to rebuild the constant pool.
    // Copying the original constant pool should be avoided because it would keep references
    // to the original class names. This is not a problem at runtime (because these entries in the
    // constant pool are never used), but confuses some tools such as Felix' maven-bundle-plugin
    // that use the constant pool to determine the dependencies of a class.
    ClassWriter cw = new ClassWriter(0);

    ClassVisitor cv = new RemappingClassAdapter(cw, remapper);

    try {
      cr.accept(cv, ClassReader.EXPAND_FRAMES);
    } catch (Throwable ise) {
      throw new MojoExecutionException("Error in ASM processing class " + name, ise);
    }

    byte[] renamedClass = cw.toByteArray();

    // Need to take the .class off for remapping evaluation
    String mappedName = remapper.map(name.substring(0, name.indexOf('.')));

    try {
      // Now we put it back on so the class file is written out with the right extension.
      jos.putNextEntry(new JarEntry(mappedName + ".class"));

      IOUtil.copy(renamedClass, jos);
    } catch (ZipException e) {
      getLogger().debug("We have a duplicate " + mappedName + " in " + jar);
    }
  }

  private boolean isFiltered(List<Filter> filters, String name) {
    for (Filter filter : filters) {
      if (filter.isFiltered(name)) {
        return true;
      }
    }

    return false;
  }

  private boolean resourceTransformed(List<ResourceTransformer> resourceTransformers, String name, InputStream is, List<Relocator> relocators) throws IOException {
    boolean resourceTransformed = false;

    for (ResourceTransformer transformer : resourceTransformers) {
      if (transformer.canTransformResource(name)) {
        getLogger().debug("Transforming " + name + " using " + transformer.getClass().getName());

        transformer.processResource(name, is, relocators);

        resourceTransformed = true;

        break;
      }
    }
    return resourceTransformed;
  }

  private void addJavaSource(Set<String> resources, JarOutputStream jos, String name, InputStream is, List<Relocator> relocators) throws IOException {
    jos.putNextEntry(new JarEntry(name));

    String sourceContent = IOUtil.toString(new InputStreamReader(is, "UTF-8"));

    for (Relocator relocator : relocators) {
      sourceContent = relocator.applyToSourceContent(sourceContent);
    }

    OutputStreamWriter writer = new OutputStreamWriter(jos, "UTF-8");
    IOUtil.copy(sourceContent, writer);
    writer.flush();

    resources.add(name);
  }

  private void addResource(Set<String> resources, JarOutputStream jos, String name, InputStream is) throws IOException {
    jos.putNextEntry(new JarEntry(name));

    IOUtil.copy(is, jos);

    resources.add(name);
  }

  class RelocatorRemapper extends Remapper {

    private final Pattern classPattern = Pattern.compile("(\\[*)?L(.+);");

    List<Relocator> relocators;

    public RelocatorRemapper(List<Relocator> relocators) {
      this.relocators = relocators;
    }

    public boolean hasRelocators() {
      return !relocators.isEmpty();
    }

    public Object mapValue(Object object) {
      if (object instanceof String) {
        String name = (String) object;
        String value = name;

        String prefix = "";
        String suffix = "";

        Matcher m = classPattern.matcher(name);
        if (m.matches()) {
          prefix = m.group(1) + "L";
          suffix = ";";
          name = m.group(2);
        }

        for (Relocator r : relocators) {
          if (r.canRelocateClass(name)) {
            value = prefix + r.relocateClass(name) + suffix;
            break;
          } else if (r.canRelocatePath(name)) {
            value = prefix + r.relocatePath(name) + suffix;
            break;
          }
        }

        return value;
      }

      return super.mapValue(object);
    }

    public String map(String name) {
      String value = name;

      String prefix = "";
      String suffix = "";

      Matcher m = classPattern.matcher(name);
      if (m.matches()) {
        prefix = m.group(1) + "L";
        suffix = ";";
        name = m.group(2);
      }

      for (Relocator r : relocators) {
        if (r.canRelocatePath(name)) {
          value = prefix + r.relocatePath(name) + suffix;
          break;
        }
      }

      return value;
    }

  }

}
