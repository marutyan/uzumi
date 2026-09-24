import java.util.Properties

plugins {
    id("com.android.application")
}

// Mozc本体と同じprotobuf 34.1の生成器とruntimeを使い、Java lite生成物とruntimeの版を一致させる。
val mozcProtobufVersion = "4.34.1"

// local.propertiesは開発端末ごとの設定であり、Git追跡外のMozc生成物の場所を受け取るために読む。
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}

/** Gradle propertyを優先し、無ければlocal.propertiesから同名の値を返す。 */
fun uzumiProperty(name: String): String? {
    return providers.gradleProperty(name).orNull ?: localProperties.getProperty(name)
}

// Mozcのnative_libs.zipとmozc.dataを置いたディレクトリ。未指定なら変換エンジンなしでビルドする。
val mozcArtifactsDir: File? = uzumiProperty("uzumi.mozcArtifactsDir")
    ?.takeIf(String::isNotBlank)
    ?.let(::File)

// APKへ入れるABI。既定は実機用のarm64-v8aだけで、emulator確認時だけx86_64を足す。
val mozcAbis: List<String> = (uzumiProperty("uzumi.mozcAbis") ?: "arm64-v8a")
    .split(',')
    .map(String::trim)
    .filter(String::isNotEmpty)

// falseにすると辞書を外したAPKを作り、minimal engineへのfallback検出を実機で確かめられる。
val mozcIncludeData: Boolean = uzumiProperty("uzumi.mozcIncludeData")?.toBoolean() ?: true

// ニューラル変換のnative生成物（`<abi>/lib*.so`）を置いたディレクトリ。`tools/neural/build_android.sh`がGradleの外で作る。
// 未指定ならニューラル変換なしでビルドし、JVMテストも通る（IMEはMozcだけで動く）。
val neuralArtifactsDir: File? = uzumiProperty("uzumi.neuralArtifactsDir")
    ?.takeIf(String::isNotBlank)
    ?.let(::File)

android {
    namespace = "dev.uzumi.ime"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.uzumi.ime"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }

    // 別プロセスの推論service（`:neural`）との受け渡しにAIDLを使う。
    buildFeatures {
        aidl = true
    }
}

// protobuf Gradle plugin 0.9.6はAGP 9の拡張型へ対応していないため、protoc成果物を直接実行する。
val protocExecutable: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

/** 開発端末のOSとCPUに対応するprotoc成果物のclassifierを返す。 */
fun protocClassifier(): String {
    val osName = System.getProperty("os.name").lowercase()
    val archName = System.getProperty("os.arch").lowercase()
    val os = when {
        osName.contains("mac") -> "osx"
        osName.contains("win") -> "windows"
        else -> "linux"
    }
    val arch = if (archName == "aarch64" || archName == "arm64") "aarch_64" else "x86_64"
    return "$os-$arch"
}

/**
 * 固定checkout（13c98988247aa711d99db9e348ec2a597d14b5cd）から複製したMozcのprotocol定義を、
 * Java liteのソースへ生成する。Kotlin側のブリッジはこの生成物だけを通してMozcへ命令を送る。
 */
abstract class GenerateMozcJavaLite : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val protoDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val protoc: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @get:Inject
    abstract val execOperations: ExecOperations

    /** protocを実行権付きで作業領域へ複製し、全`.proto`をlite形式で生成する。 */
    @TaskAction
    fun generate() {
        val output = outputDirectory.get().asFile
        fileSystem.delete { delete(output) }
        output.mkdirs()
        val protocFile = temporaryDir.resolve("protoc")
        protoc.singleFile.copyTo(protocFile, overwrite = true)
        protocFile.setExecutable(true)
        val protoRoot = protoDirectory.get().asFile
        val protoFiles = protoDirectory.asFileTree.matching { include("**/*.proto") }
            .files
            .map { it.relativeTo(protoRoot).invariantSeparatorsPath }
            .sorted()
        execOperations.exec {
            commandLine(
                listOf(
                    protocFile.absolutePath,
                    "--java_out=lite:${output.absolutePath}",
                    "--proto_path=${protoRoot.absolutePath}",
                ) + protoFiles,
            )
        }
    }
}

val generateMozcJavaLite = tasks.register<GenerateMozcJavaLite>("generateMozcJavaLite") {
    protoDirectory.set(rootProject.layout.projectDirectory.dir("third_party/mozc/proto"))
    protoc.from(protocExecutable)
    outputDirectory.set(layout.buildDirectory.dir("generated/mozc/proto-java"))
}

/**
 * Git追跡外のMozc生成物から、指定ABIのlibmozc.soだけをjniLibsの形へ取り出す。
 * 生成物が無い場合は空のディレクトリを出力し、変換エンジンなしのビルドを成立させる。
 */
abstract class PrepareMozcNativeLibs : DefaultTask() {
    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val nativeLibsZip: RegularFileProperty

    @get:Input
    abstract val abis: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @get:Inject
    abstract val archives: ArchiveOperations

    /** 出力先を作り直し、zip内の`libs/<abi>/libmozc.so`を`<abi>/libmozc.so`へ複製する。 */
    @TaskAction
    fun prepare() {
        val output = outputDirectory.get().asFile
        fileSystem.delete { delete(output) }
        output.mkdirs()
        val zip = nativeLibsZip.orNull?.asFile ?: return
        val selectedAbis = abis.get()
        fileSystem.copy {
            from(archives.zipTree(zip))
            include(selectedAbis.map { "libs/$it/libmozc.so" })
            eachFile { path = relativePath.segments.drop(1).joinToString("/") }
            includeEmptyDirs = false
            into(output)
        }
    }
}

/**
 * mozc.dataと第三者表示をassetsの形へ集める。どちらも無ければ省略し、ビルドを失敗させない。
 */
abstract class PrepareMozcAssets : DefaultTask() {
    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dataFile: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val noticeFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    /** 出力先を作り直し、辞書を`mozc/mozc.data`、表示を`licenses/mozc-NOTICE.txt`へ置く。 */
    @TaskAction
    fun prepare() {
        val output = outputDirectory.get().asFile
        fileSystem.delete { delete(output) }
        output.mkdirs()
        dataFile.orNull?.asFile?.let { data ->
            fileSystem.copy {
                from(data)
                into(output.resolve("mozc"))
            }
        }
        noticeFile.orNull?.asFile?.let { notice ->
            fileSystem.copy {
                from(notice)
                rename { "mozc-NOTICE.txt" }
                into(output.resolve("licenses"))
            }
        }
    }
}

/**
 * Git追跡外のニューラル変換の生成物から、指定ABIの共有ライブラリ（`<abi>/lib*.so`）をjniLibsの形へ集め、
 * 生成物があればllama.cppの著作権表示をassetsへ置く。生成物が無ければ空のディレクトリを出力し、
 * ニューラル変換なしのビルドを成立させる。
 */
abstract class PrepareNeuralNative : DefaultTask() {
    @get:Optional
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val artifactsDirectory: DirectoryProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val noticeFile: RegularFileProperty

    @get:Input
    abstract val abis: ListProperty<String>

    @get:OutputDirectory
    abstract val jniLibsDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val assetsDirectory: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    /** 出力先を作り直し、各ABIの共有ライブラリと表示を複製する。 */
    @TaskAction
    fun prepare() {
        val libs = jniLibsDirectory.get().asFile
        val assets = assetsDirectory.get().asFile
        fileSystem.delete { delete(libs, assets) }
        libs.mkdirs()
        assets.mkdirs()
        val artifacts = artifactsDirectory.orNull?.asFile ?: return
        var copied = false
        for (abi in abis.get()) {
            val source = artifacts.resolve(abi)
            if (!source.isDirectory) continue
            fileSystem.copy {
                from(source)
                include("*.so")
                into(libs.resolve(abi))
            }
            copied = true
        }
        val notice = noticeFile.orNull?.asFile
        if (copied && notice != null) {
            fileSystem.copy {
                from(notice)
                rename { "llama.cpp-NOTICE.txt" }
                into(assets.resolve("licenses"))
            }
        }
    }
}

val prepareNeuralNative = tasks.register<PrepareNeuralNative>("prepareNeuralNative") {
    neuralArtifactsDir?.takeIf(File::isDirectory)?.let(artifactsDirectory::set)
    rootProject.file("third_party/llama.cpp/NOTICE.txt").takeIf(File::isFile)?.let(noticeFile::set)
    abis.set(mozcAbis)
    jniLibsDirectory.set(layout.buildDirectory.dir("generated/neural/jniLibs"))
    assetsDirectory.set(layout.buildDirectory.dir("generated/neural/assets"))
}

val prepareMozcNativeLibs = tasks.register<PrepareMozcNativeLibs>("prepareMozcNativeLibs") {
    mozcArtifactsDir?.resolve("native_libs.zip")?.takeIf(File::isFile)?.let(nativeLibsZip::set)
    abis.set(mozcAbis)
    outputDirectory.set(layout.buildDirectory.dir("generated/mozc/jniLibs"))
}

val prepareMozcAssets = tasks.register<PrepareMozcAssets>("prepareMozcAssets") {
    if (mozcIncludeData) {
        mozcArtifactsDir?.resolve("mozc.data")?.takeIf(File::isFile)?.let(dataFile::set)
    }
    rootProject.file("third_party/mozc/NOTICE.txt").takeIf(File::isFile)?.let(noticeFile::set)
    outputDirectory.set(layout.buildDirectory.dir("generated/mozc/assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(
            generateMozcJavaLite,
            GenerateMozcJavaLite::outputDirectory,
        )
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            prepareMozcNativeLibs,
            PrepareMozcNativeLibs::outputDirectory,
        )
        variant.sources.assets?.addGeneratedSourceDirectory(
            prepareMozcAssets,
            PrepareMozcAssets::outputDirectory,
        )
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            prepareNeuralNative,
            PrepareNeuralNative::jniLibsDirectory,
        )
        variant.sources.assets?.addGeneratedSourceDirectory(
            prepareNeuralNative,
            PrepareNeuralNative::assetsDirectory,
        )
    }
}

dependencies {
    implementation("com.google.protobuf:protobuf-javalite:$mozcProtobufVersion")
    protocExecutable("com.google.protobuf:protoc:$mozcProtobufVersion:${protocClassifier()}@exe")
    testImplementation("junit:junit:4.13.2")
}
