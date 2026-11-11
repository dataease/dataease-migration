export const INSTANCE_ENVIRONMENTS = ["生产", "预发布", "测试"];

export function instanceEnvironment(instance) {
  if (INSTANCE_ENVIRONMENTS.includes(instance?.environment))
    return instance.environment;
  if (INSTANCE_ENVIRONMENTS.includes(instance?.tag)) return instance.tag;
  return "未设置";
}
