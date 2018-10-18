package com.dd.buildgradle

import com.dd.buildgradle.exten.ComExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class ComBuild implements Plugin<Project> {

    //默认是app，直接运行assembleRelease的时候，等同于运行app:assembleRelease
    String compilemodule = "app"

    void apply(Project project) {

        /*
         这里 主要有  app  sharecomponent，readercomponent，sharecomponentkotlin 编译会调用这个方法ß
         根目录下有打印日志 log1.txt   执行的命令是 gradlew assembleRelease
         根目录下有打印日志 log2.txt   执行的命令是 gradlew :readercomponent:assembleRelease
         */

        //获取在 gradle 定义插件的 参数
        project.extensions.create('combuild', ComExtension)

        //project.gradle.startParameter.taskNames 指的就是 gradlew 后面的参数， 例如 gradlew assembleRelease，gradlew :readercomponent:assembleRelease
        //就分别对应 assembleRelease 和  :readercomponent:assembleRelease
        String taskNames = project.gradle.startParameter.taskNames.toString()
        //如果  gradlew assembleRelease  打印的是 "taskNames is [assembleRelease]"
        //如果  gradlew :readercomponent:assembleRelease  打印的是 "taskNames is [:readercomponent:assembleRelease]"
        System.out.println("-------> taskNames is " + taskNames)

        String module = project.path.replace(":", "")   //project.path 是  :moduelname  这样的格式，这个去掉:(冒号)
        //module 是当前project 所代表的 module
        System.out.println("-------> current module is " + module)

        AssembleTask assembleTask = getTaskInfo(project.gradle.startParameter.taskNames)

        if (assembleTask.isAssemble) {  //assembleRelease 返回 isAssemble为true

            //compileMoudule 指的是当前可以运行安装app的module
            fetchMainModulename(project, assembleTask)
            //如果  gradlew assembleRelase  打印的是  "compilemodule module is app"
            //如果  gradlew :readercomponent:assembleRelease  打印的是 "compilemodule module is readercomponent"
            System.out.println("-------> compilemodule  is " + compilemodule)
        }

        if (!project.hasProperty("isRunAlone")) {  //对应 module 下 gradle.properteis 是否存在 isRunAlone属性，如果执行 app module 则是 app 下的 gradle.properties属性文件
            throw new RuntimeException("you should set isRunAlone in " + module + "'s gradle.properties")
        }

        //对于isRunAlone==true的情况需要根据实际情况修改其值，
        // 但如果是false，则不用修改，该module作为一个lib，运行module:assembleRelease则发布aar到中央仓库
        boolean isRunAlone = Boolean.parseBoolean((project.properties.get("isRunAlone")))  //每个module 都有 gradle.properties 属性 isRunAlone
        String mainmodulename = project.rootProject.property("mainmodulename")  //在根目录下 gradle.properties 设置

        if (isRunAlone && assembleTask.isAssemble) {
            //对于要编译的组件和主项目，isRunAlone修改为true，其他组件都强制修改为false
            //这就意味着组件不能引用主项目，这在层级结构里面也是这么规定的
            if (module.equals(compilemodule) || module.equals(mainmodulename)) {
                isRunAlone = true
            } else {
                isRunAlone = false
            }
        }
        project.setProperty("isRunAlone", isRunAlone)

        //根据配置添加各种组件依赖，并且自动化生成组件加载代码
        if (isRunAlone) {
            project.apply plugin: 'com.android.application'
            //mainmodulename  是整个工程的容器module  这里可能是 app，readercomponent，sharecomponent，sharecomponentkotlin 在根目录下 gradle.properties 设置
            //module 是当前正在编译的module  可能是 app，readercomponent，sharecomponent，sharecomponentkotlin
            if (!module.equals(mainmodulename)) {
                project.android.sourceSets {
                    main {
                        manifest.srcFile 'src/main/runalone/AndroidManifest.xml'
                        java.srcDirs = ['src/main/java', 'src/main/runalone/java']
                        res.srcDirs = ['src/main/res', 'src/main/runalone/res']
                        assets.srcDirs = ['src/main/assets', 'src/main/runalone/assets']
                        jniLibs.srcDirs = ['src/main/jniLibs', 'src/main/runalone/jniLibs']
                    }
                }
            }
            System.out.println("-------> apply plugin is " + 'com.android.application')
            if (assembleTask.isAssemble && module.equals(compilemodule)) {
                //这里添加依赖后，才会执行 readercomponent, sharecomponent, sharecomponentkotlin 的编译
                compileComponents(assembleTask, project)   //设置 module 之间依赖关系
                project.android.registerTransform(new ComCodeTransform(project))
            }
        } else {
            project.apply plugin: 'com.android.library'
            System.out.println("-------> apply plugin is " + 'com.android.library')
        }

    }

    /**
     * 根据当前的task，获取要运行的组件，规则如下：
     * assembleRelease ---app
     * app:assembleRelease :app:assembleRelease ---app
     * sharecomponent:assembleRelease :sharecomponent:assembleRelease ---sharecomponent
     * @param assembleTask
     */
    private void fetchMainModulename(Project project, AssembleTask assembleTask) {
        if (!project.rootProject.hasProperty("mainmodulename")) {  // root 下面的 gradle.properties 是否有 属性 mainmodulename
            throw new RuntimeException("you should set compilemodule in rootproject's gradle.properties")
        }
        if (assembleTask.modules.size() > 0 && assembleTask.modules.get(0) != null
                && assembleTask.modules.get(0).trim().length() > 0
                && !assembleTask.modules.get(0).equals("all")) {
            compilemodule = assembleTask.modules.get(0)
        } else {
            compilemodule = project.rootProject.property("mainmodulename")  //从 工程 根目录下 gradle.properties 获取 mainmodulename 属性
        }
        if (compilemodule == null || compilemodule.trim().length() <= 0) {
            compilemodule = "app"
        }
    }

    //进入参数为  [assembleRelease] 或者 [:readercomponent:assembleRelease] 是个数组
    private AssembleTask getTaskInfo(List<String> taskNames) {
        AssembleTask assembleTask = new AssembleTask()
        for (String task : taskNames) {  //可能同时 执行多个task  譬如 assembleRelease 或者 assembleDebug
            if (task.toUpperCase().contains("ASSEMBLE")
                    || task.contains("aR")
                    || task.toUpperCase().contains("TINKER")
                    || task.toUpperCase().contains("INSTALL")
                    || task.toUpperCase().contains("RESGUARD")) {
                if (task.toUpperCase().contains("DEBUG")) {
                    assembleTask.isDebug = true
                }
                assembleTask.isAssemble = true

                System.out.println("------>task is " + task);  //task is assembleRelease 或者 :readercomponent:assembleRelease

                String[] strs = task.split(":") //strs的长度 最多为2
                assembleTask.modules.add(strs.length > 1 ? strs[strs.length - 2] : "all")
                break
            }
        }
        return assembleTask
    }

    /**
     * 自动添加依赖，只在运行assemble任务的才会添加依赖，因此在开发期间组件之间是完全感知不到的，这是做到完全隔离的关键
     * 支持两种语法：module或者groupId:artifactId:version(@aar),前者之间引用module工程，后者使用maven中已经发布的aar
     * @param assembleTask
     * @param project
     */
    private void compileComponents(AssembleTask assembleTask, Project project) {
        String components
        if (assembleTask.isDebug) {
            components = (String) project.properties.get("debugComponent")
        } else {
            components = (String) project.properties.get("compileComponent")
        }

        if (components == null || components.length() == 0) {
            System.out.println("-------> there is no add dependencies ")
            return
        }
        String[] compileComponents = components.split(",")
        if (compileComponents == null || compileComponents.length == 0) {
            System.out.println("-------> there is no add dependencies ")
            return
        }
        for (String str : compileComponents) {
            System.out.println("-------> compile is " + str)  //str 的 样式 com.mrzhang.share:sharecomponent(包名:module名)
            if (str.contains(":")) {
                /**
                 * 示例语法:groupId:artifactId:version(@aar)
                 * compileComponent=com.luojilab.reader:readercomponent:1.0.0
                 * 注意，前提是已经将组件aar文件发布到maven上，并配置了相应的repositories
                 */
                project.dependencies.add("compile", str)
                System.out.println("add dependencies lib  : " + str)
            } else {
                /**
                 * 示例语法:module
                 * compileComponent=readercomponent,sharecomponent
                 */
                project.dependencies.add("compile", project.project(':' + str))  //添加 module之间的 依赖
                System.out.println("-------> add dependencies project : " + str)
            }
        }
    }

    private class AssembleTask {
        boolean isAssemble = false
        boolean isDebug = false
        List<String> modules = new ArrayList<>()
    }

}