package com.minimapresize;

public enum ScalingFilter
{
	NEAREST("Nearest neighbor (pixel sharp)"),
	BILINEAR("Bilinear (smooth)"),
	BICUBIC("Bicubic (Catmull-Rom)"),
	SHARP_BICUBIC("Sharp Bicubic"),
	MITCHELL("Bicubic (Mitchell)"),
	HYBRID("Hybrid (crisp edges)"),
	LANCZOS("Lanczos 3 (fine detail)"),
	XBR("xBR (experimental)");

	private final String label;

	ScalingFilter(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
