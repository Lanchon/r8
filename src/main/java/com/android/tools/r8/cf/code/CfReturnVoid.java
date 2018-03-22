// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.cf.code;

import com.android.tools.r8.cf.CfPrinter;
import com.android.tools.r8.naming.NamingLens;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class CfReturnVoid extends CfInstruction {

  @Override
  public void write(MethodVisitor visitor, NamingLens lens) {
    visitor.visitInsn(Opcodes.RETURN);
  }

  @Override
  public void print(CfPrinter printer) {
    printer.print(this);
  }
}
