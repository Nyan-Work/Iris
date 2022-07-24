package net.coderbot.iris.pipeline.transform;

import java.util.Optional;
import java.util.function.Function;

import org.antlr.v4.runtime.Token;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.github.douira.glsl_transformer.ast.node.Identifier;
import io.github.douira.glsl_transformer.ast.print.PrintType;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTTransformer;
import io.github.douira.glsl_transformer.cst.core.SemanticException;
import io.github.douira.glsl_transformer.cst.token_filter.ChannelFilter;
import io.github.douira.glsl_transformer.cst.token_filter.TokenChannel;
import io.github.douira.glsl_transformer.cst.token_filter.TokenFilter;
import net.coderbot.iris.IrisLogging;
import net.coderbot.iris.gbuffer_overrides.matching.InputAvailability;
import net.coderbot.iris.gl.blending.AlphaTest;
import net.coderbot.iris.gl.shader.ShaderType;
import net.coderbot.iris.pipeline.newshader.ShaderAttributeInputs;

/**
 * The transform patcher (triforce 2) uses glsl-transformer's ASTTransformer to
 * do shader transformation.
 *
 * NOTE: This patcher expects (and ensures) that the string doesn't contain any
 * (!) preprocessor directives. The only allowed ones are #extension and #pragma
 * as they are considered "parsed" directives. If any other directive appears in
 * the string, it will throw.
 * 
 * Change notes compared to existing 1.16 patches:
 * - Builtinuniformtransformer: nothing
 * - CompositeDepth: texture2D -> texture (done)
 * - Attribute: out vec4 entityColor instead of varying, iris_UV1 and related,
 * iris_vertexColorGS and following, iris_vertexColorGS in new main, in vec4
 * entityColor instead of varying, iris_vertexColor replacement added and in
 * vec4 iris_vertexColor added (done)
 */
public class TransformPatcher {
	static Logger LOGGER = LogManager.getLogger(TransformPatcher.class);
	private static ASTTransformer<Parameters> transformer;

	// TODO: Only do the NewLines patches if the source code isn't from
	// gbuffers_lines (what does this mean?)

	static TokenFilter<Parameters> parseTokenFilter = new ChannelFilter<Parameters>(TokenChannel.PREPROCESSOR) {
		@Override
		public boolean isTokenAllowed(Token token) {
			if (!super.isTokenAllowed(token)) {
				throw new SemanticException("Unparsed preprocessor directives such as '" + token.getText()
						+ "' may not be present at this stage of shader processing!");
			}
			return true;
		}
	};

	static {
		transformer = new ASTTransformer<>();
		transformer.setTransformation((tree, root, parameters) -> {
			// check for illegal references to internal Iris shader interfaces
			Optional<Identifier> violation = root.identifierIndex.prefixQueryFlat("iris_").findAny();
			if (!violation.isPresent()) {
				violation = root.identifierIndex.prefixQueryFlat("irisMain").findAny();
			}
			if (!violation.isPresent()) {
				violation = root.identifierIndex.prefixQueryFlat("moj_import").findAny();
			}
			violation.ifPresent(id -> {
				throw new SemanticException(
						"Detected a potential reference to unstable and internal Iris shader interfaces (iris_, irisMain and moj_import). This isn't currently supported. Violation: "
								+ id.getName());
			});

			Root.indexBuildSession(tree, () -> {
				switch (parameters.patch) {
					case ATTRIBUTES:
						AttributeTransformer.transform(transformer, tree, root, (AttributeParameters) parameters);
						break;
					case COMPOSITE:
						CompositeTransformer.transform(transformer, tree, root, parameters);
						break;
					case SODIUM:
						SodiumTransformer.transform(transformer, tree, root, (SodiumParameters) parameters);
						break;
					case VANILLA:
						VanillaTransformer.transform(transformer, tree, root, (VanillaParameters) parameters);
						break;
				}
			});
		});
		transformer.getInternalParser().setParseTokenFilter(parseTokenFilter);
	}

	private static String inspectPatch(
			String source,
			String patchInfo,
			Function<String, String> patcher) {
		if (source == null) {
			return null;
		}

		if (IrisLogging.ENABLE_TRANSFORM_SPAM) {
			LOGGER.debug("INPUT: " + source + " END INPUT");
		}

		long time = System.currentTimeMillis();
		String patched = patcher.apply(source);

		if (IrisLogging.ENABLE_TRANSFORM_SPAM) {
			LOGGER.debug("INFO: " + patchInfo);
			LOGGER.debug("TIME: patching took " + (System.currentTimeMillis() - time) + "ms");
			LOGGER.debug("PATCHED: " + patched + " END PATCHED");
		}
		return patched;
	}

	private static String transform(String source, Parameters parameters) {
		return transformer.transform(PrintType.COMPACT, source, parameters);
	}

	public static String patchAttributes(String source, ShaderType type, boolean hasGeometry, InputAvailability inputs) {
		return inspectPatch(source, "TYPE: " + type + " HAS_GEOMETRY: " + hasGeometry,
				str -> transform(str, new AttributeParameters(Patch.ATTRIBUTES, type,
						hasGeometry, inputs)));
	}

	public static String patchVanilla(
			String source, ShaderType type, AlphaTest alpha,
			boolean hasChunkOffset, ShaderAttributeInputs inputs, boolean hasGeometry) {
		return inspectPatch(source, " TYPE: " + type + "HAS_GEOMETRY: " + hasGeometry,
				str -> transform(str, new VanillaParameters(Patch.VANILLA,
						type, alpha, hasChunkOffset, inputs, hasGeometry)));
	}

	public static String patchSodium(
			String source, ShaderType type, AlphaTest alpha,
			ShaderAttributeInputs inputs, float positionScale, float positionOffset, float textureScale) {
		return inspectPatch(source, " TYPE: " + type,
				str -> transform(str, new SodiumParameters(Patch.SODIUM,
						type, alpha, inputs, positionScale, positionOffset, textureScale)));
	}

	public static String patchComposite(String source, ShaderType type) {
		return inspectPatch(source,
				" TYPE: " + type,
				str -> transform(str, new Parameters(Patch.COMPOSITE, type)));
	}
}
