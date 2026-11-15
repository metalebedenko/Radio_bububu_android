package ru.bububu.silero.stressprobe;
/** Accepted C272 14-argument mapping; consumes current live CorePlan, no model call. */
final class CoreArguments {
 static org.pytorch.IValue[] assemble(CorePlan core) {
  org.pytorch.IValue[] inputs=new org.pytorch.IValue[14];
  for(int i=0;i<14;i++) {
   ru.bububu.silero.coreprobe.AnnotatedCarrier.Arg arg=core.arguments.get(i);long[] shape=new long[arg.shape.length];for(int j=0;j<shape.length;j++) shape[j]=arg.shape[j];
   java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(arg.bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
   switch(arg.kind) {
    case "tensor":
     if(arg.dtype.equals("int64")) { long[] data=new long[arg.bytes.length/8];b.asLongBuffer().get(data);inputs[i]=org.pytorch.IValue.from(org.pytorch.Tensor.fromBlob(data,shape)); }
     else if(arg.dtype.equals("float32")) { float[] data=new float[arg.bytes.length/4];b.asFloatBuffer().get(data);inputs[i]=org.pytorch.IValue.from(org.pytorch.Tensor.fromBlob(data,shape)); }
     else throw new FrontendPlan.Rejected("CORE_ARG_DTYPE");break;
    case "int":inputs[i]=org.pytorch.IValue.from(Long.parseLong(arg.scalar));break;
    case "str":inputs[i]=org.pytorch.IValue.from(arg.scalar);break;
    case "bool":inputs[i]=org.pytorch.IValue.from(Boolean.parseBoolean(arg.scalar));break;
    case "none":inputs[i]=org.pytorch.IValue.optionalNull();break;
    default:throw new FrontendPlan.Rejected("CORE_ARG_KIND");
   }
  }
  return inputs;
 }
 private CoreArguments() {}
}
