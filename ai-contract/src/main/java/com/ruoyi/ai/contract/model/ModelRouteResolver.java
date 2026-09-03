package com.ruoyi.ai.contract.model;

import com.ruoyi.ai.contract.core.InvocationContext;

public interface ModelRouteResolver
{
    ResolvedModel resolve(ModelRequest request, InvocationContext context);
}
