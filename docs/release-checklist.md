# 1.0 Release Checklist

The production release is a single-JVM JVM artifact line. Native Image is a
separate compatibility lane and must not silently become a JVM release gate.

1. Run the Maven reactor on Linux x86_64 and Windows x86_64 with JDK 25.
2. Run the normal tests, actor-tck, stress suite and the opt-in million-actor
   memory test on dedicated hardware.
3. Run JMH throughput/latency and preemption benchmarks. Store the raw result,
   JVM flags, CPU model and OS with the release record.
4. Run the release-metadata profile, API baseline comparison and dependency
   vulnerability review. Inspect the generated CycloneDX SBOM.
5. Publish sources, Javadocs, checksums and signed artifacts to the internal
   Maven repository. Promote only after the hard gates pass.
6. Run the Native Image job when a supported GraalVM JDK is available and
   review reflection/resource metadata separately.
