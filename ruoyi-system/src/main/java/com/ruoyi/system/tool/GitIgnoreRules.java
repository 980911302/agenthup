package com.ruoyi.system.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简 .gitignore:尊重搜索根及其父目录里的规则,始终忽略 .git。
 */
final class GitIgnoreRules
{
    private final Path searchRoot;
    private final List<String> patterns = new ArrayList<>();

    private GitIgnoreRules(Path searchRoot)
    {
        this.searchRoot = searchRoot;
    }

    static GitIgnoreRules load(Path searchRoot)
    {
        GitIgnoreRules rules = new GitIgnoreRules(searchRoot.toAbsolutePath().normalize());
        Path dir = rules.searchRoot;
        Path repo = null;
        for (Path cur = dir; cur != null; cur = cur.getParent())
        {
            if (Files.isDirectory(cur.resolve(".git")))
            {
                repo = cur;
                break;
            }
        }
        List<Path> chain = new ArrayList<>();
        if (repo == null)
        {
            chain.add(dir);
        }
        else
        {
            for (Path cur = dir; cur != null; cur = cur.getParent())
            {
                chain.add(cur);
                if (cur.equals(repo))
                {
                    break;
                }
            }
        }
        for (int i = chain.size() - 1; i >= 0; i--)
        {
            Path gi = chain.get(i).resolve(".gitignore");
            if (Files.isRegularFile(gi))
            {
                try
                {
                    for (String line : Files.readAllLines(gi))
                    {
                        String t = line.trim();
                        if (t.isEmpty() || t.startsWith("#") || t.startsWith("!"))
                        {
                            continue;
                        }
                        if (t.endsWith("/"))
                        {
                            t = t.substring(0, t.length() - 1);
                        }
                        if (t.startsWith("/"))
                        {
                            t = t.substring(1);
                        }
                        rules.patterns.add(t);
                    }
                }
                catch (IOException ignored)
                {
                }
            }
        }
        return rules;
    }

    boolean ignores(Path path)
    {
        Path abs = path.toAbsolutePath().normalize();
        for (Path p = abs; p != null; p = p.getParent())
        {
            if (".git".equals(String.valueOf(p.getFileName())))
            {
                return true;
            }
        }
        Path rel;
        try
        {
            rel = searchRoot.relativize(abs);
        }
        catch (IllegalArgumentException e)
        {
            return false;
        }
        String posix = rel.toString().replace('\\', '/');
        String name = abs.getFileName() == null ? "" : abs.getFileName().toString();
        for (String pat : patterns)
        {
            if (matches(pat, posix, name))
            {
                return true;
            }
        }
        return false;
    }

    boolean ignoresDirectory(Path dir)
    {
        return ignores(dir);
    }

    static boolean skipWalkName(String name)
    {
        return ".git".equals(name);
    }

    private static boolean matches(String pattern, String posix, String name)
    {
        if (pattern.equals(name) || pattern.equals(posix))
        {
            return true;
        }
        if (pattern.contains("/"))
        {
            return glob(pattern, posix);
        }
        for (String part : posix.split("/"))
        {
            if (glob(pattern, part))
            {
                return true;
            }
        }
        return glob(pattern, name);
    }

    private static boolean glob(String pattern, String text)
    {
        return text.matches(toRegex(pattern));
    }

    private static String toRegex(String glob)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("^");
        for (int i = 0; i < glob.length(); i++)
        {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*')
            {
                sb.append(".*");
                i++;
            }
            else if (c == '*')
            {
                sb.append("[^/]*");
            }
            else if (c == '?')
            {
                sb.append("[^/]");
            }
            else if (".+()[]{}|^$\\".indexOf(c) >= 0)
            {
                sb.append('\\').append(c);
            }
            else
            {
                sb.append(c);
            }
        }
        sb.append("$");
        return sb.toString();
    }

}
