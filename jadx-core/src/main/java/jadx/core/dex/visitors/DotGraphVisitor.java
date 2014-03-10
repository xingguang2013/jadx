package jadx.core.dex.visitors;

import jadx.core.codegen.CodeWriter;
import jadx.core.codegen.MethodGen;
import jadx.core.dex.attributes.IAttributeNode;
import jadx.core.dex.instructions.IfNode;
import jadx.core.dex.instructions.InsnType;
import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.nodes.IContainer;
import jadx.core.dex.nodes.IRegion;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.trycatch.ExceptionHandler;
import jadx.core.utils.InsnUtils;
import jadx.core.utils.RegionUtils;
import jadx.core.utils.Utils;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DotGraphVisitor extends AbstractVisitor {

	private static final String NL = "\\l";
	private static final boolean PRINT_REGISTERS_STATES = false;

	private final File dir;
	private final boolean useRegions;
	private final boolean rawInsn;

	private CodeWriter dot;
	private CodeWriter conn;

	public DotGraphVisitor(File outDir, boolean useRegions, boolean rawInsn) {
		this.dir = outDir;
		this.useRegions = useRegions;
		this.rawInsn = rawInsn;
	}

	public DotGraphVisitor(File outDir, boolean useRegions) {
		this(outDir, useRegions, false);
	}

	@Override
	public void visit(MethodNode mth) {
		if (mth.isNoCode()) {
			return;
		}
		dot = new CodeWriter();
		conn = new CodeWriter();

		dot.startLine("digraph \"CFG for");
		dot.add(escape(mth.getParentClass().getFullName() + "." + mth.getMethodInfo().getShortId()));
		dot.add("\" {");

		if (useRegions) {
			if (mth.getRegion() == null) {
				return;
			}
			processMethodRegion(mth);
		} else {
			for (BlockNode block : mth.getBasicBlocks()) {
				processBlock(mth, block);
			}
		}

		dot.startLine("MethodNode[shape=record,label=\"{");
		dot.add(escape(mth.getAccessFlags().makeString()));
		dot.add(escape(mth.getReturnType() + " "
				+ mth.getParentClass().getFullName() + "." + mth.getName()
				+ "(" + Utils.listToString(mth.getArguments(true)) + ") "));

		String attrs = attributesString(mth);
		if (attrs.length() != 0) {
			dot.add(" | ").add(attrs);
		}
		dot.add("}\"];");

		dot.startLine("MethodNode -> ").add(makeName(mth.getEnterBlock())).add(';');

		dot.add(conn.toString());

		dot.startLine('}');
		dot.startLine();

		String fileName = Utils.escape(mth.getMethodInfo().getShortId())
				+ (useRegions ? ".regions" : "")
				+ (rawInsn ? ".raw" : "")
				+ ".dot";
		dot.save(dir, mth.getParentClass().getClassInfo().getFullPath() + "_graphs", fileName);
	}

	private void processMethodRegion(MethodNode mth) {
		processRegion(mth, mth.getRegion());
		for (ExceptionHandler h : mth.getExceptionHandlers()) {
			if (h.getHandlerRegion() != null) {
				processRegion(mth, h.getHandlerRegion());
			}
		}
		Set<BlockNode> regionsBlocks = new HashSet<BlockNode>(mth.getBasicBlocks().size());
		RegionUtils.getAllRegionBlocks(mth.getRegion(), regionsBlocks);
		for (ExceptionHandler handler : mth.getExceptionHandlers()) {
			IContainer handlerRegion = handler.getHandlerRegion();
			if (handlerRegion != null) {
				RegionUtils.getAllRegionBlocks(handlerRegion, regionsBlocks);
			}
		}
		for (BlockNode block : mth.getBasicBlocks()) {
			if (!regionsBlocks.contains(block)) {
				processBlock(mth, block, true);
			}
		}
	}

	private void processRegion(MethodNode mth, IContainer region) {
		if (region instanceof IRegion) {
			IRegion r = (IRegion) region;
			dot.startLine("subgraph " + makeName(region) + " {");
			dot.startLine("label = \"").add(r);
			String attrs = attributesString(r);
			if (attrs.length() != 0) {
				dot.add(" | ").add(attrs);
			}
			dot.add("\";");
			dot.startLine("node [shape=record,color=blue];");

			for (IContainer c : r.getSubBlocks()) {
				processRegion(mth, c);
			}

			dot.startLine('}');
		} else if (region instanceof BlockNode) {
			processBlock(mth, (BlockNode) region);
		}
	}

	private void processBlock(MethodNode mth, BlockNode block) {
		processBlock(mth, block, false);
	}

	private void processBlock(MethodNode mth, BlockNode block, boolean error) {
		String attrs = attributesString(block);
		if (PRINT_REGISTERS_STATES) {
			if (block.getStartState() != null) {
				if (attrs.length() != 0) {
					attrs += "|";
				}
				attrs += escape("RS: " + block.getStartState()) + NL;
				attrs += escape("RE: " + block.getEndState()) + NL;
			}
		}
		dot.startLine(makeName(block));
		dot.add(" [shape=record,");
		if (error) {
			dot.add("color=red,");
		}
		dot.add("label=\"{");
		dot.add(block.getId()).add("\\:\\ ");
		dot.add(InsnUtils.formatOffset(block.getStartOffset()));
		if (attrs.length() != 0) {
			dot.add('|').add(attrs);
		}
		String insns = insertInsns(mth, block);
		if (insns.length() != 0) {
			dot.add('|').add(insns);
		}
		dot.add("}\"];");

		BlockNode falsePath = null;
		List<InsnNode> list = block.getInstructions();
		if (!list.isEmpty() && list.get(0).getType() == InsnType.IF) {
			falsePath = ((IfNode) list.get(0)).getElseBlock();
		}
		for (BlockNode next : block.getSuccessors()) {
			conn.startLine(makeName(block)).add(" -> ").add(makeName(next));
			if (next == falsePath) {
				conn.add("[style=dotted]");
			}
			conn.add(';');
		}
	}

	private String attributesString(IAttributeNode block) {
		StringBuilder attrs = new StringBuilder();
		for (String attr : block.getAttributes().getAttributeStrings()) {
			attrs.append(escape(attr)).append(NL);
		}
		return attrs.toString();
	}

	private static String makeName(IContainer c) {
		String name;
		if (c instanceof BlockNode) {
			name = "Node_" + ((BlockNode) c).getId();
		} else {
			name = "cluster_" + c.getClass().getSimpleName() + "_" + c.hashCode();
		}
		return name;
	}

	private String insertInsns(MethodNode mth, BlockNode block) {
		if (rawInsn) {
			StringBuilder str = new StringBuilder();
			for (InsnNode insn : block.getInstructions()) {
				str.append(escape(insn + " " + insn.getAttributes()));
				str.append(NL);
			}
			return str.toString();
		} else {
			CodeWriter code = new CodeWriter(0);
			MethodGen.addFallbackInsns(code, mth, block.getInstructions(), false);
			String str = escape(code.newLine().toString());
			if (str.startsWith(NL)) {
				str = str.substring(NL.length());
			}
			return str;
		}
	}

	private static String escape(String string) {
		return string
				.replace("\\", "") // TODO replace \"
				.replace("/", "\\/")
				.replace(">", "\\>").replace("<", "\\<")
				.replace("{", "\\{").replace("}", "\\}")
				.replace("\"", "\\\"")
				.replace("-", "\\-")
				.replace("|", "\\|")
				.replace("\n", NL);
	}
}
