package org.benf.cfr.reader.util.output;

import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;
import org.benf.cfr.reader.bytecode.analysis.types.ClassNameUtils;
import org.benf.cfr.reader.bytecode.analysis.types.JavaTypeInstance;
import org.benf.cfr.reader.entities.attributes.AttributeCode;
import org.benf.cfr.reader.entities.attributes.AttributeLineNumberTable;
import org.benf.cfr.reader.state.TypeUsageInformation;
import org.benf.cfr.reader.util.getopt.Options;
import org.benf.cfr.reader.util.output.MethodErrorCollector.SummaryDumperMethodErrorCollector;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;

public class SinkDumperFactory implements DumperFactory {
    private static final List<OutputSinkFactory.SinkClass> justString = Collections.singletonList(OutputSinkFactory.SinkClass.STRING);
    private final OutputSinkFactory sinkFactory;
    private Options options;
    private final int version;

    public SinkDumperFactory(OutputSinkFactory sinkFactory, Options options) {
        this.sinkFactory = sinkFactory;
        this.options = options;
        this.version = 0;
    }

    private SinkDumperFactory(SinkDumperFactory other, int version) {
        this.sinkFactory = other.sinkFactory;
        this.options = other.options;
        this.version = version;
    }

    @Override
    public DumperFactory getFactoryWithPrefix(String prefix, int version) {
        return new SinkDumperFactory(this, version);
    }

    @Override
    public Dumper getNewTopLevelDumper(JavaTypeInstance classType, SummaryDumper summaryDumper, TypeUsageInformation typeUsageInformation, IllegalIdentifierDump illegalIdentifierDump) {
        List<OutputSinkFactory.SinkClass> supported = sinkFactory.getSupportedSinks(OutputSinkFactory.SinkType.JAVA, Arrays.asList(OutputSinkFactory.SinkClass.DECOMPILED_MULTIVER, OutputSinkFactory.SinkClass.DECOMPILED, OutputSinkFactory.SinkClass.TOKEN_STREAM, OutputSinkFactory.SinkClass.STRING));
        if (supported == null) supported = justString;
        MethodErrorCollector methodErrorCollector = new SummaryDumperMethodErrorCollector(classType, summaryDumper);
        return getTopLevelDumper2(classType, typeUsageInformation, illegalIdentifierDump, supported, methodErrorCollector);
    }

    @Override
    public Dumper wrapLineNoDumper(Dumper dumper) {
        List<OutputSinkFactory.SinkClass> linesSupported = sinkFactory.getSupportedSinks(OutputSinkFactory.SinkType.LINENUMBER, Collections.singletonList(OutputSinkFactory.SinkClass.LINE_NUMBER_MAPPING));
        if (linesSupported == null || linesSupported.isEmpty()) {
            return dumper;
        }

        for (OutputSinkFactory.SinkClass sinkClass : linesSupported) {
            switch (sinkClass) {
                case LINE_NUMBER_MAPPING: {

                    final OutputSinkFactory.Sink<SinkReturns.LineNumberMapping> sink = sinkFactory.getSink(OutputSinkFactory.SinkType.LINENUMBER, OutputSinkFactory.SinkClass.LINE_NUMBER_MAPPING);
                    BytecodeDumpConsumer d = new BytecodeDumpConsumer() {
                        @Override
                        public void accept(Collection<Item> items) {
                            for (final Item item : items) {
                                sink.write(new SinkReturns.LineNumberMapping() {
                                    @Override
                                    public String getClassName() {
                                        return item.getMethod().getClassFile().getClassType().getRawName();
                                    }

                                    @Override
                                    public String methodName() {
                                        return item.getMethod().getName();
                                    }

                                    @Override
                                    public String methodDescriptor() {
                                        return item.getMethod().getMethodPrototype().getOriginalDescriptor();
                                    }

                                    @Override
                                    public NavigableMap<Integer, Integer> getMappings() {
                                        return item.getBytecodeLocs();
                                    }

                                    @Override
                                    public NavigableMap<Integer, Integer> getClassFileMappings() {
                                        AttributeCode codeAttribute = item.getMethod().getCodeAttribute();
                                        if (codeAttribute == null) return null;
                                        AttributeLineNumberTable lineNumberTable = codeAttribute.getAttributes().getByName(AttributeLineNumberTable.ATTRIBUTE_NAME);
                                        if (lineNumberTable == null) return null;

                                        return lineNumberTable.getEntries();
                                    }
                                });
                            }
                        }
                    };
                    return new BytecodeTrackingDumper(dumper, d);
                }
            }
        }
        return dumper;
    }

    private Dumper getTopLevelDumper2(JavaTypeInstance classType, TypeUsageInformation typeUsageInformation, IllegalIdentifierDump illegalIdentifierDump, List<OutputSinkFactory.SinkClass> supported, MethodErrorCollector methodErrorCollector) {
        for (OutputSinkFactory.SinkClass sinkClass : supported) {
            switch (sinkClass) {
                case DECOMPILED_MULTIVER:
                    return SinkSourceClassDumper(sinkFactory.<SinkReturns.Decompiled>getSink(OutputSinkFactory.SinkType.JAVA, sinkClass), version, classType, methodErrorCollector, typeUsageInformation, illegalIdentifierDump);
                case DECOMPILED:
                    return SinkSourceClassDumper(sinkFactory.<SinkReturns.Decompiled>getSink(OutputSinkFactory.SinkType.JAVA, sinkClass), classType, methodErrorCollector, typeUsageInformation, illegalIdentifierDump);
                case STRING:
                    return SinkStringClassDumper(sinkFactory.<String>getSink(OutputSinkFactory.SinkType.JAVA, sinkClass), methodErrorCollector, typeUsageInformation, illegalIdentifierDump);
                case TOKEN_STREAM:
                    return TokenStreamClassDumper(sinkFactory.<SinkReturns.Token>getSink(OutputSinkFactory.SinkType.JAVA, sinkClass), version, classType, methodErrorCollector, typeUsageInformation, illegalIdentifierDump);
                default:
            }
        }
        OutputSinkFactory.Sink<String> stringSink = sinkFactory.getSink(OutputSinkFactory.SinkType.JAVA, OutputSinkFactory.SinkClass.STRING);
        if (stringSink == null) {
            stringSink = new NopStringSink();
        }
        return SinkStringClassDumper(stringSink, methodErrorCollector, typeUsageInformation, illegalIdentifierDump);
    }

    private Dumper TokenStreamClassDumper(final OutputSinkFactory.Sink<SinkReturns.Token> sink, int version, JavaTypeInstance classType, MethodErrorCollector methodErrorCollector, TypeUsageInformation typeUsageInformation, IllegalIdentifierDump illegalIdentifierDump) {
        return new TokenStreamDumper(sink, version, classType, methodErrorCollector, typeUsageInformation, options, illegalIdentifierDump, new MovableDumperContext());
    }

    private Dumper SinkStringClassDumper(final OutputSinkFactory.Sink<String> sink, MethodErrorCollector methodErrorCollector, TypeUsageInformation typeUsageInformation, IllegalIdentifierDump illegalIdentifierDump) {
        final StringBuilder sb = new StringBuilder();
        return new StringStreamDumper(methodErrorCollector, sb, typeUsageInformation, options, illegalIdentifierDump, new MovableDumperContext()) {
            @Override
            public void close() {
                sink.write(sb.toString());
            }
        };
    }

    private Dumper SinkSourceClassDumper(final OutputSinkFactory.Sink<SinkReturns.Decompiled> sink, JavaTypeInstance classType, MethodErrorCollector methodErrorCollector, TypeUsageInformation typeUsageInformation, IllegalIdentifierDump illegalIdentifierDump) {
        final StringBuilder sb = new StringBuilder();
        final Pair<String, String> names = ClassNameUtils.getPackageAndClassNames(classType);

        return new StringStreamDumper(methodErrorCollector, sb, typeUsageInformation, options, illegalIdentifierDump, new MovableDumperContext()) {

            @Override
            public void close() {
                final String java = sb.toString();
                SinkReturns.Decompiled res = new SinkReturns.Decompiled() {
                    @Override
                    public String getPackageName() {
                        return names.getFirst();
                    }

                    @Override
                    public String getClassName() {
                        return names.getSecond();
                    }

                    @Override
                    public String getJava() {
                        return java;
                    }
                };

                sink.write(res);
            }
        };
    }

    private Dumper SinkSourceClassDumper(final OutputSinkFactory.Sink<SinkReturns.Decompiled> sink, final int version, JavaTypeInstance classType, MethodErrorCollector methodErrorCollector, TypeUsageInformation typeUsageInformation, IllegalIdentifierDump illegalIdentifierDump) {
        final StringBuilder sb = new StringBuilder();
        final Pair<String, String> names = ClassNameUtils.getPackageAndClassNames(classType);

        return new StringStreamDumper(methodErrorCollector, sb, typeUsageInformation, options, illegalIdentifierDump, new MovableDumperContext()) {

            @Override
            public void close() {
                final String java = sb.toString();
                SinkReturns.DecompiledMultiVer res = new SinkReturns.DecompiledMultiVer() {
                    @Override
                    public String getPackageName() {
                        return names.getFirst();
                    }

                    @Override
                    public String getClassName() {
                        return names.getSecond();
                    }

                    @Override
                    public String getJava() {
                        return java;
                    }

                    @Override
                    public int getRuntimeFrom() {
                        return version;
                    }
                };

                sink.write(res);
            }
        };
    }

    @Override
    public ProgressDumper getProgressDumper() {
        List<OutputSinkFactory.SinkClass> supported = sinkFactory.getSupportedSinks(OutputSinkFactory.SinkType.PROGRESS, justString);
        if (supported == null) supported = justString;
        for (OutputSinkFactory.SinkClass sinkClass : supported) {
            switch (sinkClass) {
                case STRING:
                    return new SinkProgressDumper(sinkFactory.<String>getSink(OutputSinkFactory.SinkType.PROGRESS, sinkClass));
                default:
                    continue;
            }
        }
        OutputSinkFactory.Sink<String> stringSink = sinkFactory.getSink(OutputSinkFactory.SinkType.PROGRESS, OutputSinkFactory.SinkClass.STRING);
        if (stringSink == null) {
            stringSink = new NopStringSink();
        }
        return new SinkProgressDumper(stringSink);

    }

    @Override
    public SummaryDumper getSummaryDumper() {
        List<OutputSinkFactory.SinkClass> supported = sinkFactory.getSupportedSinks(OutputSinkFactory.SinkType.SUMMARY, justString);
        if (supported == null) supported = justString;
        for (OutputSinkFactory.SinkClass sinkClass : supported) {
            switch (sinkClass) {
                case STRING:
                    return new SinkSummaryDumper(sinkFactory.<String>getSink(OutputSinkFactory.SinkType.SUMMARY, sinkClass));
                default:
                    continue;
            }
        }
        OutputSinkFactory.Sink<String> stringSink = sinkFactory.getSink(OutputSinkFactory.SinkType.SUMMARY, OutputSinkFactory.SinkClass.STRING);
        if (stringSink == null) {
            stringSink = new NopStringSink();
        }
        return new SinkSummaryDumper(stringSink);
    }

    @Override
    public ExceptionDumper getExceptionDumper() {
        List<OutputSinkFactory.SinkClass> supported = sinkFactory.getSupportedSinks(OutputSinkFactory.SinkType.EXCEPTION, Arrays.asList(OutputSinkFactory.SinkClass.EXCEPTION_MESSAGE, OutputSinkFactory.SinkClass.STRING));
        if (supported == null) supported = justString;
        for (OutputSinkFactory.SinkClass sinkClass : supported) {
            switch (sinkClass) {
                case STRING:
                    return new SinkStringExceptionDumper(sinkFactory.<String>getSink(OutputSinkFactory.SinkType.EXCEPTION, sinkClass));
                case EXCEPTION_MESSAGE:
                    return new SinkExceptionDumper(sinkFactory.<SinkReturns.ExceptionMessage>getSink(OutputSinkFactory.SinkType.EXCEPTION, sinkClass));
                default:
                    continue;
            }
        }
        OutputSinkFactory.Sink<String> stringSink = sinkFactory.getSink(OutputSinkFactory.SinkType.EXCEPTION, OutputSinkFactory.SinkClass.STRING);
        if (stringSink == null) {
            stringSink = new NopStringSink();
        }
        return new SinkStringExceptionDumper(stringSink);
    }

    private static class NopStringSink implements OutputSinkFactory.Sink<String> {
        @Override
        public void write(String sinkable) {
        }
    }

    private static class SinkProgressDumper implements ProgressDumper {
        OutputSinkFactory.Sink<String> progressSink;

        SinkProgressDumper(OutputSinkFactory.Sink<String> progressSink) {
            this.progressSink = progressSink;
        }

        @Override
        public void analysingType(JavaTypeInstance type) {
            progressSink.write("Analysing type " + type.getRawName());
        }

        @Override
        public void analysingPath(String path) {
            progressSink.write("Analysing path " + path);
        }
    }

    private static class SinkStringExceptionDumper implements ExceptionDumper {
        private final OutputSinkFactory.Sink<String> sink;

        SinkStringExceptionDumper(OutputSinkFactory.Sink<String> sink) {
            this.sink = sink;
        }

        @Override
        public void noteException(String path, String comment, Exception e) {
            sink.write(comment);
        }
    }

    private static class SinkExceptionDumper implements ExceptionDumper {

        private OutputSinkFactory.Sink<SinkReturns.ExceptionMessage> exceptionSink;

        private SinkExceptionDumper(OutputSinkFactory.Sink<SinkReturns.ExceptionMessage> exceptionSink) {
            this.exceptionSink = exceptionSink;
        }

        @Override
        public void noteException(final String path, final String comment, final Exception e) {
            SinkReturns.ExceptionMessage res = new SinkReturns.ExceptionMessage() {
                @Override
                public String getPath() {
                    return path;
                }

                @Override
                public String getMessage() {
                    return comment;
                }

                @Override
                public Exception getThrownException() {
                    return e;
                }
            };
            exceptionSink.write(res);
        }
    }
}
