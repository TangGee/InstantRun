package com.example.tangtang.intsantrun;


import java.lang.reflect.Field;
import java.util.logging.Level;

public abstract class AbstractPatchesLoaderImpl implements PatchesLoader {
	public abstract String[] getPatchedClasses();

	public boolean load() {
		try {
			for (String className : getPatchedClasses()) {
				ClassLoader cl = getClass().getClassLoader();
				Class<?> aClass = cl.loadClass(className + "$override");
				Object o = aClass.newInstance();
				Class<?> originalClass = cl.loadClass(className);
				Field changeField = originalClass.getDeclaredField("$change");

				changeField.setAccessible(true);

				Object previous = changeField.get(null);
				if (previous != null) {
					Field isObsolete = previous.getClass().getDeclaredField(
							"$obsolete");
					if (isObsolete != null) {
						isObsolete.set(null, Boolean.valueOf(true));
					}
				}
				changeField.set(null, o);

			}
		} catch (Exception e) {

			return false;
		}
		return true;
	}
}