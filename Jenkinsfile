// 若依 AI Agent 自动部署 Jenkinsfile
// 流程:容器内从 GitHub 拉代码 → SSH 到宿主机 → 宿主机脚本
//       拉代码 → 用 Docker 跑 maven/node 容器构建 → docker build 镜像 → docker compose up 部署

pipeline {
    agent any

    // Poll SCM:每小时查一次 GitHub 仓库,仅在有新 commit 时触发构建。
    // 注意:不要在这里删工作区(见 post),否则 Poll SCM 因"工作目录不存在"
    // 无法比较上次构建 revision,会把每次轮询都误判为"有变更"而重复部署。
    triggers {
        pollSCM('H/1 * * * *')
    }

    options {
        // 整个 pipeline 30 分钟超时
        timeout(time: 30, unit: 'MINUTES')
        // 输出带时间戳,方便排查
        timestamps()
        // 保留最近 10 次构建日志
        buildDiscarder(logRotator(numToKeepStr: '10'))
        // 不允许并发执行(避免部署冲突)
        disableConcurrentBuilds()
    }

    environment {
        // 部署目标用户(容器内 SSH 到 host.docker.internal)
        DEPLOY_USER = 'zhanglinlin'
        // 宿主机上的构建部署脚本
        DEPLOY_SCRIPT = '/Users/zhanglinlin/ruoyi-ai-deploy/build-and-deploy.sh'
        // 由 Jenkins 注入宿主机构建脚本，避免脚本内写死代码仓库或分支。
        GIT_REPO_URL = 'https://github.com/980911302/agenthup.git'
        GIT_BRANCH = 'main'
    }

    stages {
        // ========================================
        // Stage 1:拉代码(Jenkins 按任务 SCM 配置从 GitHub 拉)
        // ========================================
        stage('Checkout') {
            steps {
                // 任务配的 SCM 拉代码;Poll SCM 触发器也依赖这一步
                checkout scm
                sh 'echo "=== 拉取到的 commit ===" && git log -1 --pretty=format:"%h %s%n%an %ad" --date=iso'
            }
        }

        // ========================================
        // Stage 2:在宿主机上构建 + 部署
        // ========================================
        stage('Build & Deploy on Host') {
            when {
                // 手动触发(用户点击/API)总是执行部署,尊重用户的明确意图;
                // 自动触发(Poll SCM)时,若本次 commit 与上次成功构建相同则跳过,
                // 防止误触发导致无变更重复部署。
                expression {
                    def causes = currentBuild.getBuildCauses()
                    // getBuildCauses() 返回的是 Pipeline 沙箱可序列化的 Map，
                    // 不能用 instanceof 判断真实的 Jenkins Cause 类。
                    boolean userTriggered = causes.any { cause ->
                        cause.userId != null ||
                            cause._class == 'hudson.model.Cause$UserIdCause'
                    }
                    if (userTriggered) {
                        echo "手动触发,强制执行部署"
                        return true
                    }
                    if (env.GIT_PREVIOUS_SUCCESSFUL_COMMIT &&
                        env.GIT_COMMIT &&
                        env.GIT_PREVIOUS_SUCCESSFUL_COMMIT == env.GIT_COMMIT)
                    {
                        echo "代码无变更(${env.GIT_COMMIT} 与上次成功构建相同),跳过部署"
                        currentBuild.result = 'NOT_BUILT'
                        return false
                    }
                    return true
                }
            }
            steps {
                script {
                    // 宿主机 SSH 密码(用 sshpass 走免交互)
                    // 仓库地址和分支通过环境变量传给宿主机脚本，部署端不保存 Git 凭据。
                    withCredentials([
                        usernamePassword(
                            credentialsId: 'jenkins-host-ssh',
                            usernameVariable: 'HOST_USER',
                            passwordVariable: 'HOST_PASS'
                        )
                    ]) {
                        sh '''
                            set -e
                            echo "=== SSH 到宿主机执行构建部署脚本 ==="
                            sshpass -p "$HOST_PASS" ssh -o StrictHostKeyChecking=no \
                                "$HOST_USER@host.docker.internal" \
                                "GIT_REPO_URL='$GIT_REPO_URL' GIT_BRANCH='$GIT_BRANCH' bash '$DEPLOY_SCRIPT' 2>&1"
                        '''
                    }
                }
            }
        }
    }

    post {
        success {
            script {
                if (currentBuild.result != 'NOT_BUILT') {
                    echo "✅ 部署成功"
                } else {
                    echo "⏭ 无代码变更,跳过部署"
                }
            }
        }
        failure {
            echo "❌ 部署失败,查看上面日志"
        }
        always {
            // 刻意保留工作区:cleanWs() 会删掉 workspace,导致下次 Poll SCM
            // "Working Directory does not exist"、每次都误判有变更而重复部署。
            // workspace 由 Jenkins 任务配置的 buildDiscarder 与磁盘容量管理即可。
            echo "⏭ 保留工作区(不 cleanWs),避免 Poll SCM 误判变更"
        }
    }
}
