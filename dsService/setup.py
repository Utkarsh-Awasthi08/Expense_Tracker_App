from setuptools import setup, find_packages

install_requires = [
    'Flask==3.1.1',
    'jsonpickle==4.1.1',
    'kafka_python==2.2.11',
    'langchain_core==0.3.65',
    'langchain_mistralai==0.2.10',
    'langchain_openai==0.3.23',
    'python-dotenv==1.1.0',
]

setup(
    name='ds-service',
    version='1.0',
    packages=find_packages('src'),
    package_dir={'': 'src'},
    install_requires=install_requires,
    include_package_data=True,
)